/**
 * Durable workflows and atomic selection/storage events; reviewed terms participate in confirmation
 * idempotency without changing legacy request hashes.
 */
package com.lending.offers.infrastructure;
import com.lending.offers.Json;
import com.lending.offers.domain.*;
import static com.lending.offers.infrastructure.Database.*;
import static com.lending.offers.domain.Model.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.sql.Connection;

/**
 * Durable workflows and atomic selection/storage events; reviewed terms participate in confirmation
 * idempotency without changing legacy request hashes.
 */
public final class OfferStore {
    public final Database db;
    /** Carries work data for offer store operations. */
    public record Work(String id,long version,JsonNode response,String token) {}
    /** Carries training work data for offer store operations. */
    public record TrainingWork(String id,Training request,String token) {}
    /** Initializes offer store with the supplied configuration and dependencies. */
    public OfferStore(Database db){this.db=db;}
    /** Records an invalid or conflicting event in quarantine for inspection. */
    public void reject(String id,String payload,String reason){db.tx(c->{execute(c,"INSERT INTO rejected_events VALUES(?,?,?,now()) ON CONFLICT DO NOTHING",id,Json.hash(payload),reason);return null;});}
    /** Deduplicates qualification events and schedules current versions for offer creation. */
    public void receive(String eventId,JsonNode response){
        String id=response.path("id").asText();long version=response.path("version").asLong();if(!id.matches("[A-Za-z0-9_.:-]{1,80}")||version<1||!response.path("marketingEligible").isBoolean())throw new Json.Fault(422,"Invalid qualification response");String payload=Json.write(response),hash=Json.hash(response);
        db.tx(c->{execute(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))",id);String seen=scalar(c,"SELECT fingerprint FROM inbox WHERE event_id=?",eventId);if(seen!=null){if(!seen.equals(hash))throw new Json.Fault(409,"Event ID reused with different response");return null;}
            String prior=scalar(c,"SELECT version FROM offer_work WHERE id=?",id);if(prior!=null&&Long.parseLong(prior)==version&&!hash.equals(Json.hash(Json.tree(scalar(c,"SELECT response FROM offer_work WHERE id=?",id)))))throw new Json.Fault(409,"Conflicting response at same version");
            if(prior==null||Long.parseLong(prior)<version){execute(c,"INSERT INTO offer_work(id,version,response) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET version=excluded.version,response=excluded.response,state='READY',due=now(),lease_token=NULL,lease_until=NULL,attempts=0,last_error=NULL",id,version,payload);invalidate(c,id,"SOURCE_CHANGED");}
            StorageJournal.response(c,response);execute(c,"INSERT INTO inbox(event_id,fingerprint) VALUES(?,?)",eventId,hash);return null;});
    }
    /** Leases the next available work item so one worker can process it. */
    public Work claim(){return db.tx(c->{try(var p=statement(c,"SELECT id,version,response FROM offer_work WHERE state<>'DONE' AND due<=now() AND (lease_until IS NULL OR lease_until<now()) ORDER BY due,id FOR UPDATE SKIP LOCKED LIMIT 1");var r=p.executeQuery()){if(!r.next())return null;String id=r.getString(1),token=UUID.randomUUID().toString();execute(c,"UPDATE offer_work SET lease_token=?,lease_until=now()+interval '3 minutes',attempts=attempts+1 WHERE id=?",token,id);return new Work(id,r.getLong(2),Json.tree(r.getString(3)),token);}});}
    /** Completes leased offer work only when its qualification version and lease still match. */
    public void finish(Work w,OfferSet offer,String state){db.tx(c->{String token=scalar(c,"SELECT lease_token FROM offer_work WHERE id=? AND version=? FOR UPDATE",w.id(),w.version());if(!Objects.equals(token,w.token()))return null;
        if(offer!=null)save(c,offer);execute(c,"UPDATE offer_work SET state=?,due=now()+interval '15 seconds',lease_token=NULL,lease_until=NULL,last_error=NULL WHERE id=?",state,w.id());return null;});}
    /** Persists workflow progress so processing can resume after interruption. */
    public void checkpoint(Work w,OfferSet original){db.tx(c->{String token=scalar(c,"SELECT lease_token FROM offer_work WHERE id=? AND version=? FOR UPDATE",w.id(),w.version());if(Objects.equals(token,w.token()))save(c,original);return null;});}
    /** Withdraws a personalized variant whose qualification is no longer current. */
    public OfferSet withdrawVariant(OfferSet expected){return db.tx(c->{var old=one(c,"SELECT payload FROM offers WHERE id=? FOR UPDATE",OfferSet.class,expected.id());if(old==null||!old.equals(expected))throw new Json.Fault(409,"Offer changed during revalidation");var revised=new OfferSet(old.id(),old.sourceVersion(),old.customerId(),old.riskId(),old.campaignId(),old.catalogOfferId(),old.catalogOfferVersion(),old.product(),old.currency(),old.status(),"VARIANT_WITHDRAWN",old.createdAt(),old.validUntil(),old.modelId(),old.policyVersion(),old.cohort(),old.original(),null,Map.of(),old.explanations());save(c,revised);return revised;});}
    /** Reschedules failed work while retaining its attempt history and fencing information. */
    public void retry(Work w,String reason){db.tx(c->{execute(c,"UPDATE offer_work SET due=now()+interval '5 seconds',lease_until=NULL,lease_token=NULL,last_error=? WHERE id=? AND version=? AND lease_token=?",reason,w.id(),w.version(),w.token());return null;});}
    /** Persists an offer family together with its audit and storage publication records. */
    private static void save(Connection c,OfferSet offer)throws Exception {String payload=Json.write(offer);String prior=scalar(c,"SELECT payload FROM offers WHERE id=?",offer.id());if(payload.equals(prior))return;execute(c,"INSERT INTO offers(id,source_version,customer_id,status,payload,next_check) VALUES(?,?,?,?,?,now()+interval '5 seconds') ON CONFLICT(id) DO UPDATE SET source_version=excluded.source_version,customer_id=excluded.customer_id,status=excluded.status,payload=excluded.payload,next_check=excluded.next_check",offer.id(),offer.sourceVersion(),offer.customerId(),offer.status(),payload);long sequence=Long.parseLong(scalar(c,"INSERT INTO offer_history(offer_id,payload) VALUES(?,?) RETURNING sequence",offer.id(),payload));StorageJournal.offer(c,offer,sequence,java.time.Instant.now().toString());}
    /** Withdraws stale offer availability while retaining its history and current source version. */
    private static void invalidate(Connection c,String id,String reason)throws Exception {var old=one(c,"SELECT payload FROM offers WHERE id=? FOR UPDATE",OfferSet.class,id);if(old==null||!old.status().equals("ACTIVE"))return;save(c,new OfferSet(old.id(),old.sourceVersion(),old.customerId(),old.riskId(),old.campaignId(),old.catalogOfferId(),old.catalogOfferVersion(),old.product(),old.currency(),"REVOKED",reason,old.createdAt(),old.validUntil(),old.modelId(),old.policyVersion(),old.cohort(),old.original(),old.personalized(),old.deltas(),old.explanations()));}
    /** Withdraws stale offer availability while retaining its history and current source version. */
    public void invalidate(String id,long version,String reason){db.tx(c->{String v=scalar(c,"SELECT source_version FROM offers WHERE id=? FOR UPDATE",id);if(v!=null&&Long.parseLong(v)==version)invalidate(c,id,reason);return null;});}
    /** Retrieves a persisted offer family by identifier. */
    public OfferSet get(String id){return db.tx(c->{var result=one(c,"SELECT payload FROM offers WHERE id=?",OfferSet.class,id);if(result==null)throw new Json.Fault(404,"Offer has not been created");return result;});}
    /** Returns a bounded page of offer families using the requested filters. */
    public Page<OfferSet> list(String customer,String after,int limit){limit(limit);return db.tx(c->{var rows=new ArrayList<OfferSet>();try(var p=statement(c,"SELECT payload FROM offers WHERE id>?"+(customer==null?"":" AND customer_id=?")+" ORDER BY id LIMIT ?",customer==null?new Object[]{after==null?"":after,limit}:new Object[]{after==null?"":after,customer,limit});var r=p.executeQuery()){while(r.next())rows.add(Json.read(r.getString(1),OfferSet.class));}return new Page<>(rows,rows.size()==limit?rows.get(rows.size()-1).id():null);});}
    /** Lists active offer families due for current eligibility reconciliation. */
    public List<String> due(){return db.tx(c->{var ids=new ArrayList<String>();try(var p=statement(c,"SELECT id FROM offers WHERE status='ACTIVE' AND next_check<=now() ORDER BY next_check,id LIMIT 50 FOR UPDATE SKIP LOCKED");var r=p.executeQuery()){while(r.next()){String id=r.getString(1);ids.add(id);execute(c,"UPDATE offers SET next_check=now()+interval '5 seconds' WHERE id=?",id);}}return ids;});}
    /** Returns offer families ordered by their customer-fit results. */
    public Page<OfferSet> ranked(String customer,String after,int limit){limit(limit);double score=Double.MAX_VALUE;String afterId="";if(after!=null&&!after.isEmpty())try{var cursor=Json.tree(new String(Base64.getUrlDecoder().decode(after),java.nio.charset.StandardCharsets.UTF_8));score=cursor.path("score").asDouble();afterId=cursor.path("id").asText();if(!Double.isFinite(score)||!afterId.matches("[A-Za-z0-9_.:-]{1,80}"))throw new IllegalArgumentException();}catch(Exception e){throw new Json.Fault(422,"Invalid ranking cursor");}final double cursorScore=score;final String cursorId=afterId;
        return db.tx(c->{var rows=new ArrayList<OfferSet>();double last=0;try(var p=statement(c,"WITH ranked AS (SELECT id,payload,GREATEST((payload::jsonb#>>'{original,metrics,fitScore}')::double precision,COALESCE((payload::jsonb#>>'{personalized,metrics,fitScore}')::double precision,0)) AS score FROM offers WHERE customer_id=? AND status='ACTIVE') SELECT payload,score FROM ranked WHERE score<? OR (score=? AND id>?) ORDER BY score DESC,id LIMIT ?",customer,cursorScore,cursorScore,cursorId,limit);var r=p.executeQuery()){while(r.next()){rows.add(Json.read(r.getString(1),OfferSet.class));last=r.getDouble(2);}}String next=rows.size()==limit?Base64.getUrlEncoder().withoutPadding().encodeToString(Json.write(Map.of("score",last,"id",rows.get(rows.size()-1).id())).getBytes(java.nio.charset.StandardCharsets.UTF_8)):null;return new Page<>(rows,next);});}
    /** Aggregates persisted offer comparisons for Business inspection. */
    public Object metrics(){return db.tx(c->{var groups=new ArrayList<Object>();String sql="SELECT payload::jsonb->>'product',payload::jsonb->>'modelId',count(*),count(*) FILTER(WHERE payload::jsonb->'personalized'<>'null'::jsonb),avg((payload::jsonb#>>'{deltas,fitScore}')::double precision),avg((payload::jsonb#>>'{deltas,simulatedAcceptancePercentagePoints}')::double precision),avg((payload::jsonb#>>'{deltas,monthlyPaymentUsd}')::double precision),avg((payload::jsonb#>>'{deltas,totalCostUsd}')::double precision),count(*) FILTER(WHERE (payload::jsonb#>>'{deltas,totalCostUsd}')::double precision>0) FROM offers WHERE status='ACTIVE' GROUP BY 1,2 ORDER BY 1,2 LIMIT 100";
        try(var p=statement(c,sql);var r=p.executeQuery()){while(r.next()){var row=new LinkedHashMap<String,Object>();row.put("product",r.getString(1));row.put("modelId",r.getString(2));row.put("activeOfferFamilies",r.getLong(3));row.put("personalizedFamilies",r.getLong(4));row.put("personalizationCoverage",r.getLong(4)/(double)r.getLong(3));row.put("meanFitUplift",r.getObject(5));row.put("meanSimulatedAcceptanceUpliftPp",r.getObject(6));row.put("meanPaymentChangeUsd",r.getObject(7));row.put("meanCostChangeUsd",r.getObject(8));row.put("variantsWithHigherTotalCost",r.getLong(9));groups.add(row);}}
        return Map.of("groups",groups,"stateBasis","STORED_STATE_MAY_LAG_ELIGIBILITY","scoringVersion",Personalizer.SCORING,"acceptanceBasis","UTILITY_SIMULATION_ZERO_OBSERVED_LABELS","fairnessAssessment","NOT_ESTABLISHED_NO_PROTECTED_ATTRIBUTE_LABELS","comparisonBasis","SAME_CUSTOMER_SAME_MODEL_ORIGINAL_VS_QUALIFIED_VARIANT");});}
    /** Returns retained creation and selection history for an offer family. */
    public Object history(String id,long after){return db.tx(c->{var rows=new ArrayList<Object>();try(var p=statement(c,"SELECT sequence,payload FROM offer_history WHERE offer_id=? AND sequence>? ORDER BY sequence LIMIT 50",id,after);var r=p.executeQuery()){while(r.next())rows.add(Map.of("sequence",r.getLong(1),"offer",Json.tree(r.getString(2))));}return rows;});}
    /** Persists a version-bound customer selection and its corresponding storage event atomically. */
    public Object select(OfferSet expected,Selection request){return select(expected,request,null);}
    /** Persists a version-bound customer selection and its corresponding storage event atomically. */
    public Object select(OfferSet expected,Selection request,Terms reviewedTerms){if(!Set.of("ORIGINAL","PERSONALIZED").contains(request.kind())||!request.requestId().matches("[A-Za-z0-9_.:-]{1,80}"))throw new Json.Fault(422,"Invalid selection");String fp=Json.hash(reviewedTerms==null?List.of(expected.id(),request):List.of(expected.id(),request,reviewedTerms));return db.tx(c->{execute(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))",request.requestId());String prior=scalar(c,"SELECT fingerprint FROM selections WHERE request_id=?",request.requestId());if(prior!=null){if(!prior.equals(fp))throw new Json.Fault(409,"Selection request ID conflict");return Json.tree(scalar(c,"SELECT payload FROM selections WHERE request_id=?",request.requestId()));}
        var current=one(c,"SELECT payload FROM offers WHERE id=? FOR UPDATE",OfferSet.class,expected.id());if(current==null||!current.equals(expected)||!current.status().equals("ACTIVE")||current.sourceVersion()!=request.sourceVersion()||request.kind().equals("PERSONALIZED")&&current.personalized()==null)throw new Json.Fault(409,"Offer changed; reload choices");
        if(reviewedTerms!=null&&!reviewedTerms.equals(request.kind().equals("ORIGINAL")?current.original().terms():current.personalized().terms()))throw new Json.Fault(409,"Reviewed terms changed; reload choices");
        var result=Map.of("requestId",request.requestId(),"offerId",current.id(),"sourceVersion",current.sourceVersion(),"kind",request.kind(),"selectedAt",java.time.Instant.now().toString(),"status","SELECTED_DEMO_ONLY","terms",request.kind().equals("ORIGINAL")?current.original().terms():current.personalized().terms());execute(c,"INSERT INTO selections(request_id,offer_id,fingerprint,payload) VALUES(?,?,?,?)",request.requestId(),current.id(),fp,Json.write(result));StorageJournal.selection(c,Json.tree(Json.write(result)),java.time.Instant.now().toString());return Json.tree(Json.write(result));});}
    /** Creates an idempotent automatic training request for a synthetic dataset. */
    public void autoTrain(Training request){db.tx(c->{String key="auto-train:"+request.batchId();execute(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))",key);if(scalar(c,"SELECT value FROM state WHERE key=?",key)==null){String id=UUID.randomUUID().toString();execute(c,"INSERT INTO training_jobs(id,request) VALUES(?,?)",id,Json.write(request));execute(c,"INSERT INTO state VALUES(?,?)",key,id);}return null;});}
    /** Queues a model-training job using the supplied synthetic batch and seed. */
    public String train(Training request){String id=UUID.randomUUID().toString();db.tx(c->{execute(c,"INSERT INTO training_jobs(id,request) VALUES(?,?)",id,Json.write(request));return null;});return id;}
    /** Leases the next pending model-training job for processing. */
    public TrainingWork claimTraining(){return db.tx(c->{try(var p=statement(c,"SELECT id,request FROM training_jobs WHERE status IN ('QUEUED','RUNNING') AND (lease_until IS NULL OR lease_until<now()) ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1");var r=p.executeQuery()){if(!r.next())return null;String id=r.getString(1),token=UUID.randomUUID().toString();execute(c,"UPDATE training_jobs SET status='RUNNING',lease_until=now()+interval '10 minutes',lease_token=? WHERE id=?",token,id);return new TrainingWork(id,Json.read(r.getString(2),Training.class),token);}});}
    /** Persists the fitted model and completes its training job. */
    public void trained(TrainingWork w,CohortModel model){db.tx(c->{String token=scalar(c,"SELECT lease_token FROM training_jobs WHERE id=? FOR UPDATE",w.id());if(!Objects.equals(token,w.token()))return null;execute(c,"INSERT INTO models(id,payload) VALUES(?,?) ON CONFLICT DO NOTHING",model.id(),Json.write(model));execute(c,"INSERT INTO state VALUES('model',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",model.id());execute(c,"UPDATE training_jobs SET status='COMPLETED',lease_until=NULL,error=NULL WHERE id=?",w.id());execute(c,"UPDATE offer_work SET due=now() WHERE state='WAITING_FOR_MODEL'");return null;});}
    /** Records a model-training failure so operators can inspect the unsuccessful job. */
    public void trainingFailed(TrainingWork w,String error,boolean terminal){db.tx(c->{execute(c,"UPDATE training_jobs SET status=?,error=?,lease_until=now()+interval '10 seconds' WHERE id=? AND lease_token=?",terminal?"FAILED":"QUEUED",error,w.id(),w.token());return null;});}
    /** Retrieves the fitted cohort model available for personalization. */
    public CohortModel model(){return db.tx(c->one(c,"SELECT payload FROM models WHERE id=(SELECT value FROM state WHERE key='model')",CohortModel.class));}
    /** Lists recorded generation or training jobs and their outcomes. */
    public Object jobs(){return db.tx(c->{var rows=new ArrayList<Object>();try(var p=statement(c,"SELECT id,request,status,error FROM training_jobs ORDER BY created_at DESC LIMIT 50");var r=p.executeQuery()){while(r.next()){var row=new LinkedHashMap<String,Object>();row.put("id",r.getString(1));row.put("request",Json.tree(r.getString(2)));row.put("status",r.getString(3));row.put("error",r.getString(4));rows.add(row);}}return rows;});}
    /** Lists persisted cohort models and their training metadata. */
    public Object models(){return db.tx(c->{var rows=new ArrayList<Object>();try(var p=statement(c,"SELECT payload FROM models ORDER BY created_at DESC LIMIT 50");var r=p.executeQuery()){while(r.next())rows.add(Json.tree(r.getString(1)));}return rows;});}
    /** Reads or advances the persisted response-recovery position. */
    public String cursor(){return db.tx(c->scalar(c,"SELECT value FROM state WHERE key='backfill'"));}
    /** Reads or advances the persisted response-recovery position. */
    public void cursor(String value){db.tx(c->{execute(c,"INSERT INTO state VALUES('backfill',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",value==null?"":value);return null;});}
    /** Reports component progress and availability for operational health checks. */
    public Map<String,Object> health(){return db.tx(c->{var h=new LinkedHashMap<String,Object>();for(String table:List.of("offers","inbox","rejected_events","models","selections"))h.put(table,Long.parseLong(scalar(c,"SELECT count(*) FROM "+table)));h.put("pending",Long.parseLong(scalar(c,"SELECT count(*) FROM offer_work WHERE state<>'DONE'")));h.put("waitingForModel",Long.parseLong(scalar(c,"SELECT count(*) FROM offer_work WHERE state='WAITING_FOR_MODEL'")));return h;});}
    /** Enforces the supported page-size bounds before querying stored records. */
    static void limit(int n){if(n<1||n>100)throw new Json.Fault(422,"limit must be 1-100");}
}
