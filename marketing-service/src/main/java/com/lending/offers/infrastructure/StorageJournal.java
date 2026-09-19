/**
 * Atomic minimized history/outbox with bounded replay queries. A commit-order lock makes sequence-based
 * HTTP recovery safe under concurrent writers.
 */
package com.lending.offers.infrastructure;
import com.lending.offers.Json;
import com.lending.offers.domain.Model.*;
import com.lending.offers.domain.StoredOffers.*;
import static com.lending.offers.domain.StoredOffers.*;
import static com.lending.offers.infrastructure.Database.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;

/**
 * Atomic minimized history/outbox with bounded replay queries. A commit-order lock makes sequence-based
 * HTTP recovery safe under concurrent writers.
 */
public final class StorageJournal {
    public final Database db;
    /** Initializes storage journal with the supplied configuration and dependencies. */
    public StorageJournal(Database db){this.db=db;}
    /** Reads a named string field from stored JSON evidence. */
    private static String text(JsonNode node,String key){var value=node.path(key);return value.isTextual()?value.asText():null;}
    /** Loads the latest stored snapshot of an offer family within the transaction. */
    private static Snapshot prior(Connection c,String id)throws Exception{return one(c,"SELECT payload FROM storage_current WHERE id=?",Snapshot.class,id);}
    /** Acquires the database lock required to serialize related writes. */
    private static void lock(Connection c)throws Exception{execute(c,"SELECT pg_advisory_xact_lock(hashtextextended(current_schema()||'.storage-order',0))");}
    /** Builds minimized qualification evidence for repository and enterprise copies. */
    private static Evidence evidence(JsonNode response,long version,OfferSet offer){var q=response.path("qualification");var source=response.path("source");return new Evidence(text(source,"runId"),offer==null?text(source,"riskId"):offer.riskId(),text(source,"qualificationId"),version,offer==null?text(q,"campaignId"):offer.campaignId(),q.path("campaignVersion").asInt(),offer==null?text(q,"offerId"):offer.catalogOfferId(),offer==null?q.path("offerVersion").asInt():offer.catalogOfferVersion(),response.path("version").asLong()==version?text(response.path("bureauDecision"),"id"):null,text(q,"currentDecisionId"),text(response,"eligibilityPolicyVersion"));}
    /** Extracts the numeric model-quality metrics allowed in an offer-storage event. */
    private static Map<String,Double> modelMetrics(Connection c,String model)throws Exception {if(model==null)return Map.of();String payload=scalar(c,"SELECT payload FROM models WHERE id=?",model);if(payload==null)return Map.of();var node=Json.tree(payload).path("metrics");var result=new TreeMap<String,Double>();for(String key:List.of("trainingCount","validationCount","testCount","validationSilhouette","testSilhouette","minimumTrainingCohortSize","cohortCount","observedAcceptanceLabels"))if(node.path(key).isNumber())result.put(key,node.path(key).asDouble());return result;}
    /** Commits a new storage revision and its ordered journal event together. */
    private static void persist(Connection c,Snapshot input,int phase,String origin,String at)throws Exception {
        lock(c);if(origin!=null&&scalar(c,"SELECT origin_key FROM storage_seen WHERE origin_key=?",origin)!=null)return;
        var old=prior(c,input.id());if(origin!=null)execute(c,"INSERT INTO storage_seen VALUES(?)",origin);
        long version=old==null?1:old.version()+1;var value=input.version(version);
        if(old!=null&&old.version(0).equals(value.version(0)))return;
        execute(c,"INSERT INTO storage_current VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET version=excluded.version,customer_id=excluded.customer_id,phase=excluded.phase,response_version=excluded.response_version,payload=excluded.payload",value.id(),version,value.customerId(),phase,value.qualification().responseVersion(),Json.write(value));
        String eventId=UUID.randomUUID().toString();long sequence=Long.parseLong(scalar(c,"INSERT INTO storage_events(event_id,offer_id,version,payload) VALUES(?,?,?,'') RETURNING sequence",eventId,value.id(),version));var event=new Event(eventId,1,"OFFER_STORAGE_UPDATED",at,sequence,value);execute(c,"UPDATE storage_events SET payload=? WHERE sequence=?",Json.write(event),sequence);
    }
    /** Projects a created offer family into its pre-screen and personalized storage representation. */
    public static void offer(Connection c,OfferSet offer,long history,String at)throws Exception {
        lock(c);String raw=scalar(c,"SELECT response FROM offer_work WHERE id=?",offer.id());var response=raw==null?Json.tree("{}"):Json.tree(raw);var old=prior(c,offer.id());boolean active=offer.status().equals("ACTIVE");var original=offer.original();var personalized=offer.personalized();
        Item pre=new Item("PRE_SCREEN",active?"ACTIVE":"WITHDRAWN",active,original.terms(),original.metrics(),null,original.validUntil());Item marketing=personalized==null?(old==null?null:state(old.marketing(),"WITHDRAWN",false)):new Item("MARKETING",active?"ACTIVE":"WITHDRAWN",active,personalized.terms(),personalized.metrics(),personalized.variantQualificationId(),personalized.validUntil());
        var selection=availability(old==null?null:old.selection(),offer.sourceVersion(),pre,marketing);persist(c,new Snapshot(offer.id(),0,offer.customerId(),offer.product(),offer.currency(),evidence(response,offer.sourceVersion(),offer),pre,marketing,selection,offer.modelId(),offer.policyVersion(),offer.cohort()<0?null:offer.cohort(),modelMetrics(c,offer.modelId()),offer.deltas()),3,"offer:"+history,at);
    }
    /** Records a qualification-bound customer choice in a new repository revision. */
    public static void selection(Connection c,JsonNode choice,String at)throws Exception {
        lock(c);var old=prior(c,choice.path("offerId").asText());if(old==null)throw new IllegalStateException("Selection has no stored offer");var selected=new Choice(choice.path("requestId").asText(),choice.path("sourceVersion").asLong(),choice.path("kind").asText(),com.lending.offers.domain.Model.terms(choice.path("terms")),choice.path("selectedAt").asText(),false);
        persist(c,new Snapshot(old.id(),0,old.customerId(),old.product(),old.currency(),old.qualification(),old.preScreen(),old.marketing(),availability(selected,old.qualification().responseVersion(),old.preScreen(),old.marketing()),old.modelId(),old.policyVersion(),old.cohort(),old.modelMetrics(),old.deltas()),3,"selection:"+selected.requestId(),at);
    }
    /** Updates stored availability from a qualification-response change. */
    public static void response(Connection c,JsonNode response)throws Exception {
        lock(c);String id=response.path("id").asText();long v=response.path("version").asLong();var old=prior(c,id);if(old!=null&&old.qualification().responseVersion()>=v)return;
        var q=response.path("qualification");var catalog=response.path("qualifiedOffer").path("data");Terms terms=old==null?null:old.preScreen().terms();if(response.path("assessedAmountUsd").isNumber()&&catalog.path("illustrativeAprPct").isNumber())terms=new Terms(response.path("assessedAmountUsd").decimalValue(),catalog.path("illustrativeAprPct").decimalValue(),catalog.path("annualFeeUsd").decimalValue(),catalog.path("termMonths").asInt());
        String status=response.path("marketingEligible").asBoolean()?"AWAITING_CREATION":response.path("status").asText("WITHDRAWN");Item pre=new Item("PRE_SCREEN",status,false,terms,null,null,text(response,"validUntil"));Item marketing=old==null?null:state(old.marketing(),"WITHDRAWN",false);
        persist(c,new Snapshot(id,0,response.path("customerId").asText(),q.path("product").asText(),"USD",evidence(response,v,null),pre,marketing,availability(old==null?null:old.selection(),v,pre,marketing),old==null?null:old.modelId(),old==null?null:old.policyVersion(),old==null?null:old.cohort(),old==null?Map.of():old.modelMetrics(),Map.of()),2,"response:"+id+":"+v,Instant.now().toString());
    }
    /** Creates or refreshes a hidden pre-screen catalog match from finalized Step 2 evidence. */
    public void prescreen(JsonNode match){db.tx(c->{lock(c);String id=match.path("id").asText();if(!id.matches("DR-[a-f0-9]{60}"))throw new Json.Fault(422,"Invalid pre-screen identity");String phase=scalar(c,"SELECT phase FROM storage_current WHERE id=?",id);if(phase!=null&&Integer.parseInt(phase)>1)return null;var q=match.path("qualification");var evidence=evidence(match,0,null);var pre=new Item("PRE_SCREEN",match.path("status").asText(),false,com.lending.offers.domain.Model.terms(match.path("terms")),null,null,text(match,"validUntil"));persist(c,new Snapshot(id,0,match.path("customerId").asText(),q.path("product").asText(),"USD",evidence,pre,null,null,null,null,null,Map.of(),Map.of()),1,null,Instant.now().toString());return null;});}
    /**
     * Creates storage history for existing offer families inside the migration transaction. It reconciles
     * the final authoritative state after replay because transaction timestamps can precede actual writes.
     */
    public static void backfill(Connection c)throws Exception {

        try(var p=statement(c,"SELECT kind,identity,payload,at FROM (SELECT 'O' AS kind,sequence::text AS identity,payload,recorded_at AS at,sequence AS ordinal FROM offer_history UNION ALL SELECT 'S',request_id,payload,created_at,0::bigint FROM selections) old ORDER BY at,kind,ordinal,identity")){
            p.setFetchSize(200);try(var r=p.executeQuery()){
            while(r.next())if(r.getString(1).equals("O"))offer(c,Json.read(r.getString(3),OfferSet.class),Long.parseLong(r.getString(2)),r.getTimestamp(4).toInstant().toString());else selection(c,Json.tree(r.getString(3)),r.getTimestamp(4).toInstant().toString());
            }
        }

        try(var p=statement(c,"SELECT o.payload,(SELECT max(h.sequence) FROM offer_history h WHERE h.offer_id=o.id) FROM offers o ORDER BY o.id")){p.setFetchSize(200);try(var r=p.executeQuery()){while(r.next())offer(c,Json.read(r.getString(1),OfferSet.class),-r.getLong(2),Instant.now().toString());}}
        try(var p=statement(c,"SELECT response FROM offer_work ORDER BY id");var r=p.executeQuery()){while(r.next())response(c,Json.tree(r.getString(1)));}
    }
    /** Retrieves the current authoritative repository snapshot of an offer family. */
    public Event latest(String id){return db.tx(c->{var e=one(c,"SELECT payload FROM storage_events WHERE offer_id=? ORDER BY version DESC LIMIT 1",Event.class,id);if(e==null)throw new Json.Fault(404,"Stored offer not found");return e;});}
    /** Returns a bounded, commit-ordered event page for downstream recovery. */
    public Page<Event> events(long after,int limit){OfferStore.limit(limit);if(after<0)throw new Json.Fault(422,"after must be non-negative");return db.tx(c->{var rows=new ArrayList<Event>();try(var p=statement(c,"SELECT payload FROM storage_events WHERE sequence>? ORDER BY sequence LIMIT ?",after,limit);var r=p.executeQuery()){while(r.next())rows.add(Json.read(r.getString(1),Event.class));}return new Page<>(rows,rows.size()==limit?Long.toString(rows.get(rows.size()-1).sequence()):null);});}
    /** Lists current repository snapshots with bounded filtering and pagination. */
    public Page<Snapshot> list(String after,int limit){OfferStore.limit(limit);return db.tx(c->{var rows=new ArrayList<Snapshot>();try(var p=statement(c,"SELECT payload FROM storage_current WHERE id>? ORDER BY id LIMIT ?",after==null?"":after,limit);var r=p.executeQuery()){while(r.next())rows.add(Json.read(r.getString(1),Snapshot.class));}return new Page<>(rows,rows.size()==limit?rows.get(rows.size()-1).id():null);});}
    /** Returns the retained revisions of a stored offer family. */
    public List<Event> history(String id,long after){if(after<0)throw new Json.Fault(422,"after must be non-negative");return db.tx(c->{var rows=new ArrayList<Event>();try(var p=statement(c,"SELECT payload FROM storage_events WHERE offer_id=? AND version>? ORDER BY version LIMIT 50",id,after);var r=p.executeQuery()){while(r.next())rows.add(Json.read(r.getString(1),Event.class));}return rows;});}
    /** Reports component progress and availability for operational health checks. */
    public Map<String,Object> health(){return db.tx(c->Map.of("storedFamilies",Long.parseLong(scalar(c,"SELECT count(*) FROM storage_current")),"events",Long.parseLong(scalar(c,"SELECT count(*) FROM storage_events")),"lastSequence",Long.parseLong(scalar(c,"SELECT coalesce(max(sequence),0) FROM storage_events")),"pendingPublication",Long.parseLong(scalar(c,"SELECT count(*) FROM storage_events WHERE sent_at IS NULL"))));}
}
