/**
 * Durable queue with idempotent admission, keyset batch expansion, expiring leases and fenced
 * completion/outbox writes.
 */
package com.lending.engine.bureau.infrastructure;

import static com.lending.engine.bureau.infrastructure.BureauDatabase.*;
import com.lending.engine.bureau.domain.Bureau.*;
import com.lending.engine.bureau.domain.Bureau;
import com.lending.engine.bureau.application.BureauPorts.Source;
import com.lending.engine.domain.Model.Problem;
import com.lending.engine.infrastructure.Json;
import java.sql.*;
import java.util.*;

/**
 * Durable queue with idempotent admission, keyset batch expansion, expiring leases and fenced
 * completion/outbox writes.
 */
public final class BureauQueue {
    public static final String RESULTS="bureau.qualification.completed.v1",INPUT="bureau.qualification.requested.v1",DLQ="bureau.qualification.rejected.v1";
    public final BureauDatabase db;
    public final String inputTopic,resultsTopic,dlqTopic;
    /** Initializes bureau queue with the supplied configuration and dependencies. */
    public BureauQueue(BureauDatabase db){this(db,"");}
    /** Initializes bureau queue with the supplied configuration and dependencies. */
    public BureauQueue(BureauDatabase db,String namespace){this.db=db;inputTopic=namespace+INPUT;resultsTopic=namespace+RESULTS;dlqTopic=namespace+DLQ;}
    /** Admits a uniquely identified bureau request after checking its content fingerprint. */
    public Map<String,Object> submit(Submit input){return db.tx(c->{
        Bureau.id(input.requestId(),"requestId");Bureau.reference(input.source());String fingerprint=hash(input.source());var ref=input.source();
        try(var p=statement(c,"INSERT INTO bureau_work(id,fingerprint,run_id,risk_id,qualification_id) VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET id=EXCLUDED.id RETURNING fingerprint,status,attempts,result",input.requestId(),fingerprint,ref.runId(),ref.riskId(),ref.qualificationId());var r=p.executeQuery()){
            r.next();if(!fingerprint.equals(r.getString(1)))throw new Problem(409,"requestId was already used for a different qualification");var value=new LinkedHashMap<String,Object>();value.put("requestId",input.requestId());value.put("status",r.getString(2));value.put("attempts",r.getInt(3));value.put("result",r.getString(4)==null?null:Json.read(r.getString(4),Result.class));return value;
        }
    });}
    /** Inserts bureau work with the source references needed for durable processing. */
    private void insert(Connection c,Submit input)throws SQLException {
        Bureau.id(input.requestId(),"requestId");Bureau.reference(input.source());String fingerprint=hash(input.source());var ref=input.source();
        execute(c,"INSERT INTO bureau_work(id,fingerprint,run_id,risk_id,qualification_id) VALUES(?,?,?,?,?) ON CONFLICT(id) DO NOTHING",input.requestId(),fingerprint,ref.runId(),ref.riskId(),ref.qualificationId());
        if(!fingerprint.equals(scalar(c,"SELECT fingerprint FROM bureau_work WHERE id=?",input.requestId())))throw new Problem(409,"requestId was already used for a different qualification");
    }
    /** Persists a replayable batch manifest and verifies duplicate-request consistency. */
    public Map<String,Object> batch(BatchRequest request){
        Bureau.id(request.requestId(),"requestId");if(request.runIds()==null||request.runIds().isEmpty()||request.runIds().size()>1000||new HashSet<>(request.runIds()).size()!=request.runIds().size())throw new Problem(422,"Supply 1-1000 distinct finalized run IDs per manifest");
        request.runIds().forEach(id->Bureau.id(id,"runId"));return db.tx(c->{String fingerprint=hash(request);
            execute(c,"INSERT INTO bureau_batches(id,fingerprint,payload) VALUES(?,?,?) ON CONFLICT(id) DO NOTHING",request.requestId(),fingerprint,Json.write(request));
            if(!fingerprint.equals(scalar(c,"SELECT fingerprint FROM bureau_batches WHERE id=?",request.requestId())))throw new Problem(409,"Batch requestId was reused with different runs");return batchStatus(c,request.requestId());});
    }
    /** Expands the next bounded page of a stored manifest into individual bureau work. */
    public boolean expand(Source source){return db.tx(c->{
        try(var p=statement(c,"SELECT id,payload,run_index,cursor_ordinal FROM bureau_batches WHERE NOT expanded AND error IS NULL ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED");var r=p.executeQuery()){
            if(!r.next())return false;String id=r.getString(1);var request=Json.read(r.getString(2),BatchRequest.class);int index=r.getInt(3),cursor=r.getInt(4);
            List<SourceRow> rows;
            try{rows=source.page(request.runIds().get(index),cursor,200);}catch(Problem e){execute(c,"UPDATE bureau_batches SET error=?,expanded=true WHERE id=?",e.getMessage(),id);return true;}
            var checkpoint=c.setSavepoint();
            var expectedIds=new ArrayList<String>();var expectedHashes=new ArrayList<String>();
            try(var work=statement(c,"INSERT INTO bureau_work(id,fingerprint,run_id,risk_id,qualification_id) VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET id=EXCLUDED.id WHERE bureau_work.fingerprint=EXCLUDED.fingerprint");var member=statement(c,"INSERT INTO bureau_batch_items VALUES(?,?) ON CONFLICT DO NOTHING")){
                for(var row:rows){var q=row.qualification();if(q.outcome().equals("QUALIFIED")){
                    String runId=request.runIds().get(index),itemId="B-"+hash(List.of(id,runId,q.id())).substring(0,60);var ref=new Reference(runId,q.riskId(),q.id());
                    work.setString(1,itemId);work.setString(2,hash(ref));work.setString(3,runId);work.setString(4,q.riskId());work.setString(5,q.id());work.addBatch();
                    expectedIds.add(itemId);expectedHashes.add(hash(ref));
                    member.setString(1,id);member.setString(2,itemId);member.addBatch();
                }}
                work.executeBatch();
                String conflicts=scalar(c,"SELECT count(*) FROM unnest(?::text[],?::text[]) AS expected(id,fingerprint) JOIN bureau_work w USING(id) WHERE w.fingerprint<>expected.fingerprint",c.createArrayOf("text",expectedIds.toArray()),c.createArrayOf("text",expectedHashes.toArray()));
                if(!conflicts.equals("0")){c.rollback(checkpoint);execute(c,"UPDATE bureau_batches SET error='Batch item identifier conflict',expanded=true WHERE id=?",id);return true;}
                member.executeBatch();
            }
            if(rows.isEmpty()){index++;cursor=-1;}else cursor=rows.get(rows.size()-1).ordinal();
            execute(c,"UPDATE bureau_batches SET run_index=?,cursor_ordinal=?,expanded=? WHERE id=?",index,cursor,index==request.runIds().size(),id);return true;
        }
    });}
    /** Leases the next available work item so one worker can process it. */
    public Work claim(){return db.tx(c->{String token=UUID.randomUUID().toString();
        try(var p=statement(c,"WITH candidate AS (SELECT id FROM bureau_work WHERE ((status IN ('PENDING','RETRY') AND available_at<=now()) OR (status='PROCESSING' AND lease_until<now())) ORDER BY available_at,id LIMIT 1 FOR UPDATE SKIP LOCKED) UPDATE bureau_work w SET status='PROCESSING',attempts=w.attempts+1,lease_token=?,lease_until=now()+interval '45 seconds' FROM candidate WHERE w.id=candidate.id RETURNING w.*",token);var r=p.executeQuery()){
            return r.next()?new Work(r.getString("id"),r.getString("fingerprint"),r.getString("run_id"),r.getString("risk_id"),r.getString("qualification_id"),r.getString("status"),r.getInt("attempts"),token,r.getString("result")):null;
        }
    });}
    /** Commits a leased bureau result and its outbox event only while the worker still owns the lease. */
    public boolean finish(Work work,Result result){return db.tx(c->{
        String eventId="decision-"+hash(work.id()).substring(0,60);
        int changed=execute(c,"WITH finished AS (UPDATE bureau_work SET status=?,result=?,completed_at=now(),lease_token=NULL,lease_until=NULL WHERE id=? AND status='PROCESSING' AND lease_token=? AND lease_until>now() RETURNING id) INSERT INTO bureau_outbox(id,topic,event_key,payload) SELECT ?,?,?,? FROM finished ON CONFLICT DO NOTHING",result.status(),Json.write(result),work.id(),work.leaseToken(),eventId,resultsTopic,work.riskId(),Json.write(Map.of("eventId",eventId,"type",RESULTS,"result",result)));
        return changed==1;
    });}
    /** Reschedules failed work while retaining its attempt history and fencing information. */
    public void retry(Work work){db.tx(c->{execute(c,"UPDATE bureau_work SET status='RETRY',available_at=now()+(? * interval '1 second'),lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?",Math.min(60,1<<Math.min(work.attempts(),6)),work.id(),work.leaseToken());return null;});}
    /** Retrieves the persisted bureau request and any completed result. */
    public Map<String,Object> get(String id){return db.tx(c->status(c,id));}
    /** Returns the processing state and result of a bureau request. */
    private Map<String,Object> status(Connection c,String id)throws SQLException {
        try(var p=statement(c,"SELECT id,status,attempts,result FROM bureau_work WHERE id=?",id);var r=p.executeQuery()){
            if(!r.next())throw new Problem(404,"Bureau request not found");var value=new LinkedHashMap<String,Object>();value.put("requestId",r.getString(1));value.put("status",r.getString(2));value.put("attempts",r.getInt(3));value.put("result",r.getString(4)==null?null:Json.read(r.getString(4),Result.class));return value;
        }
    }
    /** Returns aggregate progress for a stored bureau batch. */
    public Map<String,Object> batchStatus(String id){return db.tx(c->batchStatus(c,id));}
    /** Returns aggregate progress for a stored bureau batch. */
    private Map<String,Object> batchStatus(Connection c,String id)throws SQLException {
        try(var p=statement(c,"SELECT expanded,error,run_index,cursor_ordinal FROM bureau_batches WHERE id=?",id);var r=p.executeQuery()){
            if(!r.next())throw new Problem(404,"Batch not found");var counts=new TreeMap<String,Long>();
            try(var p2=statement(c,"SELECT w.status,count(*) FROM bureau_batch_items b JOIN bureau_work w ON w.id=b.request_id WHERE b.batch_id=? GROUP BY w.status",id);var rs=p2.executeQuery()){while(rs.next())counts.put(rs.getString(1),rs.getLong(2));}
            boolean pending=counts.keySet().stream().anyMatch(s->Set.of("PENDING","PROCESSING","RETRY").contains(s));
            var value=new LinkedHashMap<String,Object>();value.put("requestId",id);value.put("expanded",r.getBoolean(1));value.put("error",r.getString(2));value.put("runIndex",r.getInt(3));value.put("cursor",r.getInt(4));value.put("counts",counts);value.put("status",!r.getBoolean(1)?"EXPANDING":pending?"PROCESSING":r.getString(2)!=null||counts.containsKey("FAILED")||counts.containsKey("INVALIDATED")?"COMPLETED_WITH_ERRORS":"COMPLETED");return value;
        }
    }
    /** Lists bureau requests using bounded pagination. */
    public List<Map<String,Object>> list(boolean batches,String after,int limit){if(limit<1||limit>100)throw new Problem(422,"limit must be 1-100");return db.tx(c->{var rows=new ArrayList<Map<String,Object>>();
        try(var p=statement(c,"SELECT id FROM "+(batches?"bureau_batches":"bureau_work")+" WHERE id>? ORDER BY id LIMIT ?",after==null?"":after,limit);var r=p.executeQuery()){while(r.next())rows.add(batches?batchStatus(c,r.getString(1)):status(c,r.getString(1)));}return rows;});}
    /** Lists the individual request identifiers admitted by a bureau batch. */
    public List<Map<String,Object>> batchItems(String id,String after,int limit){if(limit<1||limit>100)throw new Problem(422,"limit must be 1-100");return db.tx(c->{var rows=new ArrayList<Map<String,Object>>();
        try(var p=statement(c,"SELECT request_id FROM bureau_batch_items WHERE batch_id=? AND request_id>? ORDER BY request_id LIMIT ?",id,after==null?"":after,limit);var r=p.executeQuery()){while(r.next())rows.add(status(c,r.getString(1)));}return rows;});}
    /** Deduplicates and durably admits a received bureau-request event. */
    public void event(String eventId,String raw,Submit request,String rejection){db.tx(c->{String fingerprint=hash(raw);int created=execute(c,"INSERT INTO bureau_inbox(event_id,fingerprint) VALUES(?,?) ON CONFLICT DO NOTHING",eventId,fingerprint);
        if(created==0){if(!fingerprint.equals(scalar(c,"SELECT fingerprint FROM bureau_inbox WHERE event_id=?",eventId)))throw new Problem(409,"Event ID reused with different payload");return null;}
        if(rejection==null)insert(c,request);else outbox(c,"rejected-"+hash(eventId).substring(0,60),dlqTopic,eventId.substring(0,Math.min(80,eventId.length())),Map.of("eventId",eventId,"reason",rejection));return null;});}
    /** Appends a bureau event to the transactional delivery outbox. */
    public static void outbox(Connection c,String id,String topic,String key,Object value)throws SQLException{execute(c,"INSERT INTO bureau_outbox(id,topic,event_key,payload) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",id,topic,key,Json.write(value));}
    /** Reports component progress and availability for operational health checks. */
    public Map<String,Object> health(){return db.tx(c->{var result=new LinkedHashMap<String,Object>();result.put("database","UP");result.put("pendingEvents",Long.parseLong(scalar(c,"SELECT count(*) FROM bureau_outbox WHERE sent_at IS NULL")));result.put("policy",Bureau.POLICY);result.put("mode","LOCAL_DEMONSTRATION");return result;});}
}
