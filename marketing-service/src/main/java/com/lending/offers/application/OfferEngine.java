/**
 * Restartable personalization and customer choices; presentation confirmations recheck customer,
 * qualification version and reviewed terms.
 */
package com.lending.offers.application;
import com.lending.offers.Json;
import com.lending.offers.domain.*;
import com.lending.offers.infrastructure.*;
import static com.lending.offers.domain.Model.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Restartable personalization and customer choices; presentation confirmations recheck customer,
 * qualification version and reviewed terms.
 */
public final class OfferEngine implements AutoCloseable {
    public final OfferStore store;private final Source source;private final ScheduledExecutorService workers=Executors.newScheduledThreadPool(5);
    private volatile String backfill="STARTING",training="IDLE",creation="IDLE",reconciliation="STARTING";
    /** Initializes offer engine with the supplied configuration and dependencies. */
    public OfferEngine(OfferStore store,Source source){this.store=store;this.source=source;}
    /** Starts training, personalization, recovery and eligibility-reconciliation workers. */
    public void start(){for(int i=0;i<2;i++)workers.scheduleWithFixedDelay(()->safe(this::process),0,100,TimeUnit.MILLISECONDS);workers.scheduleWithFixedDelay(()->safe(this::trainOne),0,500,TimeUnit.MILLISECONDS);workers.scheduleWithFixedDelay(()->safe(this::backfill),0,3000,TimeUnit.MILLISECONDS);workers.scheduleWithFixedDelay(()->safe(this::reconcile),0,1000,TimeUnit.MILLISECONDS);}
    /** Requests initial or batch-based model training when eligible synthetic data is available. */
    public void automaticTraining(){if(store.model()==null)store.autoTrain(new Training(BootstrapPopulation.BATCH,73107));workers.scheduleWithFixedDelay(()->safe(()->{for(var batch:source.get("simulation-batches"))store.autoTrain(new Training(batch.path("id").asText(),batch.path("seed").asLong()));}),0,3000,TimeUnit.MILLISECONDS);}
    /** Runs a background operation while recording failures without terminating the scheduler. */
    private void safe(Runnable r){try{r.run();}catch(Exception e){System.err.println("Offer worker retry: "+e.getClass().getSimpleName());}}
    /** Recovers missed qualification responses through the engine HTTP API. */
    public void backfill(){try{String after=store.cursor();var page=source.get("responses?limit=100"+(after==null||after.isEmpty()?"":"&after="+after));for(var r:page.path("items"))store.receive("snapshot:"+r.path("id").asText()+":"+r.path("version").asLong(),r);store.cursor(page.path("nextAfter").isNull()?null:page.path("nextAfter").asText());backfill="RUNNING";}catch(Exception e){backfill="RETRYING";throw e;}}
    /** Queues explicit training against a completed synthetic population. */
    public String train(Training request){if(request.batchId()==null||!request.batchId().matches("[A-Za-z0-9_.:-]{1,80}"))throw new Json.Fault(422,"A completed simulation batch ID is required");return store.train(request);}
    /** Fits and stores the next leased training job, recording failure without activating an invalid model. */
    public void trainOne(){var job=store.claimTraining();if(job==null)return;try{training="RUNNING";if(job.request().batchId().equals(BootstrapPopulation.BATCH)){store.trained(job,Cohorts.train(job.id(),job.request(),BootstrapPopulation.generate()));training="IDLE";return;}var rows=new ArrayList<JsonNode>();int offset=0,total=-1;do{var page=source.get("features?batchId="+job.request().batchId()+"&limit=200&offset="+offset);int count=page.path("total").asInt();if(count<60||count>10000||total>=0&&total!=count)throw new Json.Fault(422,"Training requires a stable batch of 60-10000 customers");total=count;var items=page.path("items");if(!items.isArray()||items.isEmpty()&&offset<total)throw new Json.Fault(503,"Incomplete feature page");for(var f:items){if(!f.path("origin").asText().equals("SYNTHETIC"))throw new Json.Fault(422,"Only synthetic customers can train this demo model");rows.add(f);}offset+=items.size();}while(offset<total);if(rows.stream().map(x->x.path("customerId").asText()).distinct().count()!=total)throw new Json.Fault(422,"Duplicate/missing training customers");store.trained(job,Cohorts.train(job.id(),job.request(),rows));training="IDLE";}catch(Exception e){boolean terminal=e instanceof Json.Fault f&&f.status<500;store.trainingFailed(job,terminal?e.getMessage():"Source unavailable; automatic retry",terminal);training=terminal?"FAILED":"RETRYING";}}
    /**
     * Activates the qualified original and evaluates ranked personalized candidates against authoritative
     * rules.
     */
    public void process(){var work=store.claim();if(work==null)return;try{
        creation="RUNNING";if(!work.response().path("marketingEligible").asBoolean()){store.finish(work,null,"DONE");return;}
        var context=source.get("contexts/"+work.id());var response=context.path("response");if(response.path("version").asLong()!=work.version()){store.receive("snapshot:"+work.id()+":"+response.path("version").asLong(),response);store.finish(work,null,"DONE");return;}
        var model=store.model();var features=context.path("features");var original=terms(context.path("original"));String product=response.path("qualification").path("product").asText(),until=response.path("validUntil").asText();double[] weights=model==null?Cohorts.preference(features):Cohorts.weights(model,features);var originalMetrics=Personalizer.score(original,original,product,features,weights);
        String created=Instant.now().toString();try{var prior=store.get(work.id());if(prior.sourceVersion()==work.version())created=prior.createdAt();}catch(Json.Fault e){if(e.status!=404)throw e;}
        Alternative baseline=new Alternative("ORIGINAL",original,originalMetrics,null,until),personalized=null;String state=model==null?"WAITING_FOR_MODEL":"NO_QUALIFIED_IMPROVEMENT";var reasons=new ArrayList<String>();reasons.add("Local demonstration; automatic activation is not loan funding.");reasons.add("Acceptance is a utility simulation with zero observed response labels.");reasons.add("Credit-card cost uses a one-year full-limit balance; loan cost uses amortization.");
        var qualified=response.path("qualification");store.checkpoint(work,new OfferSet(work.id(),work.version(),response.path("customerId").asText(),response.path("source").path("riskId").asText(),qualified.path("campaignId").asText(),qualified.path("offerId").asText(),qualified.path("offerVersion").asInt(),product,"USD","ACTIVE",model==null?"WAITING_FOR_MODEL":"PERSONALIZATION_PENDING",created,until,model==null?null:model.id(),context.path("policyVersion").asText(),model==null?-1:Cohorts.cohort(model,features),baseline,null,Map.of(),List.copyOf(reasons)));
        if(model!=null){int tried=0;for(var candidate:Personalizer.candidates(context,model)){if(tried++>=12)break;String request="V-"+Json.hash(List.of(work.id(),work.version(),context.path("policyVersion").asText(),candidate.terms())).substring(0,60);var approval=source.post("variants",Map.of("requestId",request,"responseId",work.id(),"responseVersion",work.version(),"policyVersion",context.path("policyVersion").asText(),"terms",candidate.terms()));if(approval.path("approved").asBoolean()){
            if(!terms(approval.path("terms")).equals(candidate.terms())||approval.path("responseVersion").asLong()!=work.version())throw new Json.Fault(503,"Variant approval does not match proposal");source.get("approvals/"+request);personalized=new Alternative("PERSONALIZED",candidate.terms(),candidate.metrics(),request,approval.path("validUntil").asText());state="PERSONALIZED";reasons.add("COBOL envelope, underwriting and bureau approved this exact variant.");break;}}
            reasons.add("Ranked by "+Personalizer.SCORING+"; model "+model.id()+". Searched qualified catalog family only.");}
        var fresh=source.get("contexts/"+work.id());if(fresh.path("response").path("version").asLong()!=work.version())throw new Json.Fault(409,"Qualification changed during offer creation");
        var q=response.path("qualification");var offer=new OfferSet(work.id(),work.version(),response.path("customerId").asText(),response.path("source").path("riskId").asText(),q.path("campaignId").asText(),q.path("offerId").asText(),q.path("offerVersion").asInt(),product,"USD","ACTIVE",state,created,until,model==null?null:model.id(),context.path("policyVersion").asText(),model==null?-1:Cohorts.cohort(model,features),baseline,personalized,personalized==null?Map.of():Personalizer.deltas(originalMetrics,personalized.metrics()),List.copyOf(reasons));
        store.finish(work,offer,model==null?"WAITING_FOR_MODEL":"DONE");
    }catch(Json.Fault e){if(e.status==409||e.status==404){try{var latest=source.get("contexts/"+work.id()).path("response");if(latest.path("version").asLong()==work.version()){store.retry(work,"Variant changed; original remains available, retrying");return;}store.receive("snapshot:"+work.id()+":"+latest.path("version").asLong(),latest);}catch(Json.Fault check){if(check.status>=500){store.retry(work,"Authority unavailable; retrying");return;}}store.invalidate(work.id(),work.version(),"SOURCE_INVALID");store.finish(work,null,"DONE");}else store.retry(work,"Engine unavailable; automatic retry");}catch(Exception e){store.retry(work,"Processing failed; automatic retry");creation="RETRYING";}}
    /** Loads an offer family and rechecks current original and personalized eligibility. */
    public OfferSet current(String id){var offer=store.get(id);if(!offer.status().equals("ACTIVE"))throw new Json.Fault(409,"Offer is no longer active");try{
        var ctx=source.get("contexts/"+id);if(ctx.path("response").path("version").asLong()!=offer.sourceVersion()||!Instant.parse(offer.validUntil()).isAfter(Instant.now()))throw new Json.Fault(409,"Source qualification changed or expired");
        if(offer.personalized()!=null)try{var approval=source.get("approvals/"+offer.personalized().variantQualificationId());if(!terms(approval.path("terms")).equals(offer.personalized().terms()))throw new Json.Fault(409,"Variant terms no longer match approval");}catch(Json.Fault e){if(e.status!=409&&e.status!=404)throw e;return store.withdrawVariant(offer);}return offer;
    }catch(Json.Fault e){if(e.status==409||e.status==404)store.invalidate(id,offer.sourceVersion(),"ELIGIBILITY_REVOKED");throw e;}}
    /** Returns currently qualified offer families owned by the requested customer. */
    public Page<OfferSet> customer(String customer,String after,int limit){var page=store.ranked(customer,after,limit);var rows=new ArrayList<OfferSet>();for(var offer:page.items())try{rows.add(current(offer.id()));}catch(Json.Fault e){if(e.status!=409&&e.status!=404)throw e;}return new Page<>(rows,page.nextAfter());}
    /** Records a validated customer choice and its idempotency key. */
    public Object select(String id,Selection request){if(request==null||request.kind()==null||request.requestId()==null)throw new Json.Fault(422,"Selection fields required");var offer=current(id);if(request.sourceVersion()!=offer.sourceVersion()||request.kind().equals("PERSONALIZED")&&offer.personalized()==null)throw new Json.Fault(409,"Requested choice is no longer available");return store.select(offer,request);}
    /** Records a customer choice only when its owner, qualification version and reviewed terms still match. */
    public Object selectPresented(String customerId,String id,PresentedSelection request){
        if(request==null||request.requestId()==null||request.kind()==null||request.terms()==null||!Set.of("ORIGINAL","PERSONALIZED").contains(request.kind()))throw new Json.Fault(422,"Request ID, version, choice and reviewed terms are required");
        if(!store.get(id).customerId().equals(customerId))throw new Json.Fault(404,"Offer not found for this customer");
        var offer=current(id);var alternative=request.kind().equals("ORIGINAL")?offer.original():offer.personalized();
        if(request.sourceVersion()!=offer.sourceVersion()||alternative==null||!alternative.terms().equals(request.terms()))throw new Json.Fault(409,"Offer changed; review current terms before confirming");
        return store.select(offer,new Selection(request.requestId(),request.sourceVersion(),request.kind()),request.terms());
    }
    /** Rechecks active offer families and withdraws choices whose authority has changed. */
    public void reconcile(){try{for(String id:store.due())try{current(id);}catch(Json.Fault e){if(e.status>=500)throw e;}reconciliation="RUNNING";}catch(Exception e){reconciliation="RETRYING";throw e;}}
    /** Reports component progress and availability for operational health checks. */
    public Map<String,Object> health(){var h=store.health();h.put("backfill",backfill);h.put("training",training);h.put("creation",creation);h.put("reconciliation",reconciliation);h.put("acceptanceMetrics","SIMULATED_NOT_CALIBRATED");return h;}
    /** Releases the resources owned by this component. */
    public void close(){workers.shutdownNow();try{workers.awaitTermination(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
