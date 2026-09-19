/**
 * Automatic DMG execution reads repository HTTP, rechecks qualified choices and COBOL rules, and repairs
 * versioned local exports without turning shutdown into withdrawal.
 */
package com.lending.engine.execution;
import com.lending.engine.execution.Execution.Package;
import com.lending.engine.execution.Execution.*;
import com.lending.engine.application.CustomerEngine;
import com.lending.engine.marketing.application.MarketingEngine;
import com.lending.engine.offers.OfferSource;
import com.lending.engine.storage.StorageClient;
import com.lending.engine.infrastructure.Json;
import com.lending.engine.domain.Model.*;
import com.fasterxml.jackson.databind.JsonNode;
import static com.lending.engine.bureau.infrastructure.BureauDatabase.hash;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Automatic DMG execution reads repository HTTP, rechecks qualified choices and COBOL rules, and repairs
 * versioned local exports without turning shutdown into withdrawal.
 */
public final class ExecutionEngine implements AutoCloseable {
    public final ExecutionStore store;public final OutboundFiles files;private final StorageClient repository;private final CustomerEngine customers;private final MarketingEngine marketing;private final OfferSource authority;private final CobolExecutionPolicy policy;private final Clock clock;private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();private volatile String state="STARTING",recovery="STARTING";private volatile boolean closing;
    /** Initializes execution engine with the supplied configuration and dependencies. */
    public ExecutionEngine(ExecutionStore store,StorageClient repository,CustomerEngine customers,MarketingEngine marketing,OfferSource authority,Path root,Clock clock){this(store,repository,customers,marketing,authority,root,root.resolve("runtime/outbound"),clock);}
    /** Initializes execution engine with the supplied configuration and dependencies. */
    public ExecutionEngine(ExecutionStore store,StorageClient repository,CustomerEngine customers,MarketingEngine marketing,OfferSource authority,Path root,Path output,Clock clock){this.store=store;this.repository=repository;this.customers=customers;this.marketing=marketing;this.authority=authority;this.clock=clock;policy=new CobolExecutionPolicy(root);files=new OutboundFiles(output);}
    /** Starts repository recovery and background campaign-package execution. */
    public void start(){worker.scheduleWithFixedDelay(()->{try{tick();state="RUNNING";}catch(Exception e){state="RETRYING";}},0,200,TimeUnit.MILLISECONDS);}
    /** Processes due campaign tasks and repairs or withdraws their outbound bundles. */
    public synchronized void tick(){try{long cursor=store.cursor();store.admit(repository.get("events?after="+cursor+"&limit=100"),cursor);recovery="RUNNING";}catch(Exception e){recovery="RETRYING";}for(var task:store.due())process(task);}
    /** Assembles current choices, applies COBOL execution rules and publishes an eligible campaign package. */
    private void process(ExecutionStore.Task task){try{var response=repository.get("candidates?customerId="+task.customerId()+"&campaignId="+task.campaignId());if(response.path("pending").asBoolean()){withdraw(task,"PERSONALIZATION_PENDING");store.reschedule(task.id(),"WAITING_FOR_OFFERS","Training or offer personalization is running",1);return;}var contact=customers.contact(task.customerId());if(contact==null||!contact.source().equals("SYNTHETIC")){withdraw(task,"SYNTHETIC_CONTACT_REQUIRED");store.reschedule(task.id(),"SUPPRESSED","Save this customer to create synthetic contacts",2);return;}var draft=assemble(task,response.path("items"),contact);if(draft==null){withdraw(task,"NO_CURRENT_QUALIFIED_OFFERS");store.reschedule(task.id(),"NO_ELIGIBLE_OFFERS","No currently qualified alternatives",2);return;}
        assertFinancial(draft);String[] decision={"APPROVED"};var prepared=store.prepare(draft,policy.policy,clock.instant(),history->{var c=customers.get(task.customerId());decision[0]=policy.evaluate(c.current().data(),marketing.suppression(task.customerId()),true,!contact.preferredChannels().isEmpty(),history,clock.instant());return decision[0];});if(!decision[0].equals("APPROVED")){if(prepared!=null)store.publish(prepared,files::write);store.reschedule(task.id(),"SUPPRESSED",decision[0],2);return;}
        if(!store.filesReady(prepared)||!files.ready(prepared))store.publish(prepared,p->{assertCurrent(p);files.write(p);});customers.executionCompleted(task.customerId(),authority.context(prepared.leadResponseId()).response().source().runId());store.reschedule(task.id(),"EXPORTED","Local synthetic files ready; no delivery is implied",1);
    }catch(Problem e){if(closing)return;withdraw(task,e.status>=500?"AUTHORITY_UNAVAILABLE":"ELIGIBILITY_CHANGED");store.reschedule(task.id(),e.status>=500?"RETRYING":"NO_ELIGIBLE_OFFERS",e.status>=500?"Dependency unavailable; retrying":"Current eligibility failed",2);}catch(Exception e){if(closing)return;try{withdraw(task,"RETRYING");store.reschedule(task.id(),"RETRYING","Dependency or file write failed; retrying",2);}catch(Exception unavailable){state="RETRYING";}}}
    /** Invalidates a package and removes its active local outbound files. */
    private void withdraw(ExecutionStore.Task task,String reason){var value=store.invalidate(task.id(),reason);if(value!=null&&(!store.filesReady(value)||!files.ready(value)))store.publish(value,files::write);}
    /**
     * Builds one customer/campaign package while honoring the latest valid selection or highest-fit
     * choice.
     */
    private Package assemble(ExecutionStore.Task task,JsonNode rows,Contact contact){var alternatives=new ArrayList<Alternative>();var runs=new TreeSet<String>();JsonNode latestChoice=null;String chosenResponse=null;
        for(var row:rows){var offer=row.path("offer");if(!offer.path("customerId").asText().equals(task.customerId())||!offer.path("campaignId").asText().equals(task.campaignId())||!offer.path("status").asText().equals("ACTIVE"))throw new Problem(503,"Repository candidate correlation failed");var snapshot=row.path("stored");runs.add(snapshot.path("qualification").path("runId").asText());for(String field:List.of("original","personalized")){var a=offer.path(field);if(a.isNull()||a.isMissingNode())continue;alternatives.add(new Alternative(offer.path("id").asText(),offer.path("sourceVersion").asLong(),offer.path("riskId").asText(),offer.path("catalogOfferId").asText(),offer.path("catalogOfferVersion").asInt(),field.equals("original")?"ORIGINAL":"PERSONALIZED",Json.MAPPER.convertValue(a.path("terms"),OfferSource.Terms.class),a.path("metrics").path("fitScore").asDouble(),a.path("metrics").path("simulatedAcceptance").asDouble(),a.path("variantQualificationId").isNull()?null:a.path("variantQualificationId").asText(),a.path("validUntil").asText()));}
            var choice=snapshot.path("selection");if(!choice.isNull()&&choice.path("available").asBoolean()&&choice.path("sourceVersion").asLong()==offer.path("sourceVersion").asLong()&&(latestChoice==null||Instant.parse(choice.path("selectedAt").asText()).isAfter(Instant.parse(latestChoice.path("selectedAt").asText())))){latestChoice=choice;chosenResponse=offer.path("id").asText();}}
        if(alternatives.isEmpty())return null;alternatives.sort(Comparator.comparingDouble(Alternative::fitScore).reversed().thenComparing(Alternative::responseId).thenComparing(Alternative::kind));var lead=alternatives.get(0);if(latestChoice!=null){String id=chosenResponse,kind=latestChoice.path("kind").asText();lead=alternatives.stream().filter(a->a.responseId().equals(id)&&a.kind().equals(kind)).findFirst().orElse(lead);}String until=alternatives.stream().map(a->Instant.parse(a.validUntil())).min(Comparator.naturalOrder()).orElseThrow().toString();return new Package(task.id(),0,task.customerId(),task.campaignId(),"DX-"+hash(List.of(task.customerId(),task.campaignId(),runs)).substring(0,60),contact.preferredChannels().isEmpty()?"":contact.preferredChannels().get(0),contact,lead.responseId(),lead.kind(),latestChoice==null?null:latestChoice.path("requestId").asText(),List.copyOf(alternatives),policy.version,"ACTIVE",null,until,clock.instant().toString());}
    /** Rejects an offer choice whose financial terms differ from its qualified source. */
    private void assertFinancial(Package p){if(!Instant.parse(p.validUntil()).isAfter(clock.instant()))throw new Problem(409,"Package expired");for(var a:p.alternatives()){var context=authority.context(a.responseId());if(context.response().version()!=a.responseVersion()||!context.response().customerId().equals(p.customerId())||!context.response().qualification().campaignId().equals(p.campaignId())||!context.response().qualification().offerId().equals(a.catalogOfferId())||context.response().qualification().offerVersion()!=a.catalogOfferVersion())throw new Problem(409,"Qualification changed");if(a.kind().equals("ORIGINAL")){if(!a.terms().equals(context.original()))throw new Problem(409,"Original terms changed");}else{var approval=authority.check(a.variantQualificationId());if(!approval.responseId().equals(a.responseId())||approval.responseVersion()!=a.responseVersion()||!approval.terms().equals(a.terms()))throw new Problem(409,"Variant approval changed");}}}
    /** Rejects an offer choice whose authoritative qualification or terms are no longer current. */
    private void assertCurrent(Package p){if(!p.status().equals("ACTIVE")||!p.policyVersion().equals(policy.version))throw new Problem(409,"Package is not current");var contact=customers.contact(p.customerId());if(!Objects.equals(contact,p.contact())||contact.preferredChannels().isEmpty()||!contact.preferredChannels().get(0).equals(p.channel()))throw new Problem(409,"Contact preferences changed");assertFinancial(p);String result=policy.evaluate(customers.get(p.customerId()).current().data(),marketing.suppression(p.customerId()),true,true,new History(true,0,0),clock.instant());if(!result.equals("APPROVED"))throw new Problem(409,result);}
    /** Loads a campaign package after checking its current qualification state. */
    public Package current(String id){var value=store.get(id);assertCurrent(value);if(!store.filesReady(value))throw new Problem(409,"Package files are not ready");return value;}
    /** Lists current campaign packages associated with a customer. */
    public Object customer(String id){customers.get(id);var rows=new ArrayList<Object>();var page=Json.MAPPER.valueToTree(store.list(id,null,100));for(var row:page.path("items"))try{rows.add(current(row.path("id").asText()));}catch(Problem e){if(e.status!=404&&e.status!=409)throw e;}return Map.of("items",rows,"tasks",store.tasks(id));}
    /** Returns a verified outbound file only while its package remains current and authorized. */
    public synchronized Object download(String id,String format){var value=current(id);try{return files.download(value,format);}catch(IllegalArgumentException e){throw new Problem(422,e.getMessage());}catch(Exception e){throw new Problem(503,"Package file not ready; retry");}}
    /** Dispatches the HTTP method and path to the corresponding application operation. */
    public Object route(String[] p,Map<String,String> q){try{if(p.length==2)return switch(p[1]){case "health"->health();case "policy"->Map.of("policy",policy.policy,"version",policy.version);case "packages"->store.list(q.get("customerId"),q.get("after"),Integer.parseInt(q.getOrDefault("limit","20")));case "tasks"->store.tasks(q.get("customerId"));default->throw new Problem(404,"Execution route not found");};if(p.length==3&&p[1].equals("packages"))return current(p[2]);if(p.length==4&&p[1].equals("packages")){if(p[3].equals("history"))return store.history(p[2],Long.parseLong(q.getOrDefault("after","0")));if(p[3].equals("files"))return download(p[2],q.getOrDefault("format","csv"));}throw new Problem(404,"Execution route not found");}catch(NumberFormatException e){throw new Problem(422,"Invalid pagination value");}}
    /** Reports component progress and availability for operational health checks. */
    public Map<String,Object> health(){var h=store.health();h.put("worker",state);h.put("repositoryRecovery",recovery);h.put("repositoryAfter",store.cursor());h.put("automatic",true);h.put("outboundRoot",files.directory("PK-"+"0".repeat(60)).getParent().toString());return h;}
    /** Releases the resources owned by this component. */
    public void close(){closing=true;worker.shutdownNow();try{worker.awaitTermination(20,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}store.close();}
}
