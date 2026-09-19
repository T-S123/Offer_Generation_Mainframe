/**
 * PostgreSQL revisions, checkpoints and outbox commit together; admission sequence prevents late or
 * backlogged bureau results from restoring stale eligibility.
 */
package com.lending.engine.response.infrastructure;

import static com.lending.engine.bureau.infrastructure.BureauDatabase.*;
import com.lending.engine.bureau.infrastructure.*;
import com.lending.engine.bureau.domain.Bureau.*;
import com.lending.engine.domain.Model.Problem;
import com.lending.engine.infrastructure.Json;
import com.lending.engine.response.domain.DecisionResponse;
import com.lending.engine.response.domain.DecisionResponse.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * PostgreSQL revisions, checkpoints and outbox commit together; admission sequence prevents late or
 * backlogged bureau results from restoring stale eligibility.
 */
public final class ResponseRepository {
    public final BureauDatabase db;public final String qualificationTopic,decisionTopic;private final long checkSeconds;
    /** Initializes response repository with the supplied configuration and dependencies. */
    public ResponseRepository(BureauDatabase db,String namespace){this.db=db;qualificationTopic=namespace+DecisionResponse.QUALIFICATIONS;decisionTopic=namespace+DecisionResponse.DECISIONS;checkSeconds=Long.getLong("response.check.seconds",5);if(checkSeconds<1||checkSeconds>3600)throw new IllegalArgumentException("response.check.seconds must be 1-3600");}
    /** Derives the stable response identifier from the marketing run and qualification. */
    public static String id(Reference ref){return "DR-"+hash(List.of(ref.runId(),ref.qualificationId())).substring(0,60);}
    /** Carries pending data for response repository operations. */
    public record Pending(String requestId,Result result) {}
    /** Returns a bounded page of completed bureau results awaiting projection. */
    public List<Pending> pending(int limit){return db.tx(c->{var rows=new ArrayList<Pending>();
        try(var p=statement(c,"SELECT id,result FROM bureau_work WHERE result IS NOT NULL AND NOT response_projected AND response_retry_at<=now() ORDER BY response_retry_at,response_sequence LIMIT ?",limit);var r=p.executeQuery()){while(r.next())rows.add(new Pending(r.getString(1),Json.read(r.getString(2),Result.class)));}return rows;});}
    /** Returns the newest completed bureau result that has not yet been projected for a qualification. */
    public Pending latestUnprojected(Reference ref){return db.tx(c->{try(var p=statement(c,"SELECT id,result,response_projected FROM bureau_work WHERE run_id=? AND qualification_id=? AND result IS NOT NULL ORDER BY response_sequence DESC LIMIT 1",ref.runId(),ref.qualificationId());var r=p.executeQuery()){return r.next()&&!r.getBoolean(3)?new Pending(r.getString(1),Json.read(r.getString(2),Result.class)):null;}});}
    /** Advances recovery past terminal work that cannot produce an approved response. */
    public void failed(String requestId){db.tx(c->{execute(c,"UPDATE bureau_work SET response_retry_at=now()+interval '5 seconds',response_error='SOURCE_OR_STORAGE_UNAVAILABLE' WHERE id=? AND NOT response_projected",requestId);return null;});}
    /**
     * Atomically persists a response revision, checkpoint and outbox events. Newer completed bureau
     * results take precedence so backlog recovery cannot briefly restore an obsolete approval.
     */
    public void project(Pending pending,Evidence evidence,Instant now){db.tx(c->{
        var result=pending.result();String id=id(result.source());
        execute(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,73104001))",id);
        long sequence;try(var p=statement(c,"SELECT response_sequence,response_projected FROM bureau_work WHERE id=? FOR UPDATE",pending.requestId());var r=p.executeQuery()){if(!r.next()||r.getBoolean(2))return null;sequence=r.getLong(1);}
        String eventId="BD-"+hash(pending.requestId()).substring(0,60);
        BureauQueue.outbox(c,eventId,decisionTopic,result.riskId(),new DecisionEvent(eventId,1,"BUREAU_DECISION_PUBLISHED",now.toString(),result.requestId(),result.source(),evidence.qualification().customerId(),result.status(),DecisionResponse.brief(result.decision()),result.reason()));
        Response prior=one(c,"SELECT payload FROM decision_responses WHERE id=? FOR UPDATE",Response.class,id);
        long priorSequence=prior==null?-1:Long.parseLong(scalar(c,"SELECT work_sequence FROM decision_responses WHERE id=?",id));
        long latestSequence=Long.parseLong(scalar(c,"SELECT MAX(response_sequence) FROM bureau_work WHERE run_id=? AND qualification_id=? AND result IS NOT NULL",result.source().runId(),result.source().qualificationId()));

        if(sequence>priorSequence&&sequence==latestSequence){var response=DecisionResponse.initial(id,prior==null?1:prior.version()+1,result,evidence,now);save(c,response,sequence,now);}
        execute(c,"UPDATE bureau_work SET response_projected=true,response_error=NULL WHERE id=?",pending.requestId());return null;
    });}
    /** Persists a changed response and appends its qualification and decision events. */
    private void save(Connection c,Response value,long sequence,Instant now)throws SQLException {
        Instant next=now.plusSeconds(checkSeconds);if(value.validUntil()!=null&&Instant.parse(value.validUntil()).isBefore(next))next=Instant.parse(value.validUntil());
        execute(c,"INSERT INTO decision_responses(id,run_id,qualification_id,customer_id,version,work_sequence,eligible,next_check,payload) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET version=EXCLUDED.version,work_sequence=EXCLUDED.work_sequence,eligible=EXCLUDED.eligible,next_check=EXCLUDED.next_check,payload=EXCLUDED.payload",value.id(),value.source().runId(),value.source().qualificationId(),value.customerId(),value.version(),sequence,value.marketingEligible(),OffsetDateTime.ofInstant(next,ZoneOffset.UTC),Json.write(value));
        String eventId="QR-"+hash(List.of(value.id(),value.version())).substring(0,60);
        BureauQueue.outbox(c,eventId,qualificationTopic,value.source().riskId(),new Event(eventId,1,"QUALIFICATION_UPDATED",now.toString(),value));
        execute(c,"INSERT INTO decision_response_history VALUES(?,?,?,?)",value.id(),value.version(),eventId,Json.write(value));
    }
    /** Loads the stored current response without replacing authoritative source validation. */
    public Response get(String id){return db.tx(c->required(c,id,false));}
    /** Returns the required value or raises an application error when it is absent. */
    private Response required(Connection c,String id,boolean lock)throws SQLException {Response r=one(c,"SELECT payload FROM decision_responses WHERE id=?"+(lock?" FOR UPDATE":""),Response.class,id);if(r==null)throw new Problem(404,"Decision response not found");return r;}
    /** Persists a changed eligibility revision while preventing stale responses from reactivation. */
    public Response revalidate(String id,Evidence evidence,Instant now){return db.tx(c->{var prior=required(c,id,true);var next=DecisionResponse.revalidate(prior,evidence,now);
        if(next!=prior)save(c,next,Long.parseLong(scalar(c,"SELECT work_sequence FROM decision_responses WHERE id=?",id)),now);
        else execute(c,"UPDATE decision_responses SET next_check=? WHERE id=?",OffsetDateTime.ofInstant(prior.validUntil()!=null&&Instant.parse(prior.validUntil()).isBefore(now.plusSeconds(checkSeconds))?Instant.parse(prior.validUntil()):now.plusSeconds(checkSeconds),ZoneOffset.UTC),id);
        return next;});}
    /** Schedules a response for a later eligibility check after a temporary dependency failure. */
    public void defer(String id,Instant now){db.tx(c->{execute(c,"UPDATE decision_responses SET next_check=? WHERE id=?",OffsetDateTime.ofInstant(now.plusSeconds(checkSeconds),ZoneOffset.UTC),id);return null;});}
    /** Returns responses due for expiry or source revalidation. */
    public List<String> due(Instant now,int limit){return db.tx(c->{var ids=new ArrayList<String>();try(var p=statement(c,"SELECT id FROM decision_responses WHERE eligible AND next_check<=? ORDER BY next_check,id LIMIT ?",OffsetDateTime.ofInstant(now,ZoneOffset.UTC),limit);var r=p.executeQuery()){while(r.next())ids.add(r.getString(1));}return ids;});}
    /** Returns a bounded page of response identifiers for recovery or reconciliation. */
    public List<String> ids(String customer,String after,int limit){return db.tx(c->{var ids=new ArrayList<String>();String sql="SELECT id FROM decision_responses WHERE id>?"+(customer==null?"":" AND customer_id=? AND eligible")+" ORDER BY id LIMIT ?";
        Object[] args=customer==null?new Object[]{after==null?"":after,limit}:new Object[]{after==null?"":after,customer,limit};try(var p=statement(c,sql,args);var r=p.executeQuery()){while(r.next())ids.add(r.getString(1));}return ids;});}
    /** Returns stored response revisions in audit order. */
    public List<Map<String,Object>> history(String id,long after,int limit){return db.tx(c->{required(c,id,false);var rows=new ArrayList<Map<String,Object>>();
        try(var p=statement(c,"SELECT h.payload,h.event_id,o.sent_at,o.attempts,o.last_attempt_at,o.last_error,o.replay_count FROM decision_response_history h JOIN bureau_outbox o ON o.id=h.event_id WHERE h.response_id=? AND h.version>? ORDER BY h.version LIMIT ?",id,after,limit);var r=p.executeQuery()){while(r.next()){var row=new LinkedHashMap<String,Object>();row.put("response",Json.read(r.getString(1),Response.class));row.put("eventId",r.getString(2));row.put("deliveryStatus",r.getString(3)==null?"PENDING":"PUBLISHED");row.put("publishedAt",r.getString(3));row.put("attempts",r.getInt(4));row.put("lastAttemptAt",r.getString(5));row.put("lastError",r.getString(6));row.put("replayCount",r.getInt(7));rows.add(row);}}return rows;});}
    /** Appends delivery events for the current response without changing its business version. */
    public Map<String,Object> replay(String id){return db.tx(c->{var current=required(c,id,true);String eventId=scalar(c,"SELECT event_id FROM decision_response_history WHERE response_id=? AND version=?",id,current.version());
        execute(c,"UPDATE bureau_outbox SET sent_at=NULL,replay_count=replay_count+1 WHERE id=?",eventId);return Map.of("eventId",eventId,"responseId",id,"version",current.version(),"status","QUEUED");});}
    /** Reports component progress and availability for operational health checks. */
    public Map<String,Object> health(){return db.tx(c->{var value=new LinkedHashMap<String,Object>();value.put("pendingResponses",Long.parseLong(scalar(c,"SELECT count(*) FROM bureau_work WHERE result IS NOT NULL AND NOT response_projected")));value.put("projectionErrors",Long.parseLong(scalar(c,"SELECT count(*) FROM bureau_work WHERE NOT response_projected AND response_error IS NOT NULL")));value.put("eligibleResponses",Long.parseLong(scalar(c,"SELECT count(*) FROM decision_responses WHERE eligible")));value.put("pendingPublications",Long.parseLong(scalar(c,"SELECT count(*) FROM bureau_outbox WHERE sent_at IS NULL AND topic IN (?,?)",qualificationTopic,decisionTopic)));value.put("policy",DecisionResponse.POLICY);return value;});}
}
