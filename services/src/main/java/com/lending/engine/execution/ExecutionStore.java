/**
 * Campaign-owned PostgreSQL journal, recovery cursor and export allocations; retries and cancellation
 * never erase dispatch history.
 */
package com.lending.engine.execution;
import com.lending.engine.execution.Execution.Package;
import com.lending.engine.execution.Execution.*;
import com.lending.engine.infrastructure.Json;
import com.lending.engine.domain.Model.Problem;
import com.lending.engine.storage.StorageContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.*;
import static com.lending.engine.bureau.infrastructure.BureauDatabase.*;
import java.sql.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/**
 * Campaign-owned PostgreSQL journal, recovery cursor and export allocations; retries and cancellation
 * never erase dispatch history.
 */
public final class ExecutionStore implements AutoCloseable {
    public final String schema;private final HikariDataSource pool;
    /** Carries task data for execution store operations. */
    public record Task(String id,String customerId,String campaignId) {}
    /** Initializes execution store with the supplied configuration and dependencies. */
    public ExecutionStore(String url,String schema){this.schema=schema;if(!schema.matches("[a-z][a-z0-9_]{0,62}")||Set.of("public","marketing_offers","customer_offer_read","enterprise_offer_warehouse").contains(schema))throw new IllegalArgumentException("Dedicated execution schema required");try{var uri=URI.create(url);var auth=uri.getRawUserInfo().split(":",2);var config=new HikariConfig();config.setJdbcUrl("jdbc:postgresql://"+uri.getHost()+":"+(uri.getPort()<0?5432:uri.getPort())+uri.getRawPath());config.setUsername(URLDecoder.decode(auth[0],StandardCharsets.UTF_8));config.setPassword(URLDecoder.decode(auth[1],StandardCharsets.UTF_8));try(var c=DriverManager.getConnection(config.getJdbcUrl(),config.getUsername(),config.getPassword())){execute(c,"CREATE SCHEMA IF NOT EXISTS "+schema);}config.setConnectionInitSql("SET search_path TO "+schema);config.setMaximumPoolSize(4);config.setConnectionTimeout(5000);config.addDataSourceProperty("connectTimeout","5");config.addDataSourceProperty("socketTimeout","20");config.setPoolName(schema);pool=new HikariDataSource(config);tx(c->{execute(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))",schema+".migration");String sql;try(var in=getClass().getResourceAsStream("/db/execution/V001__campaign_execution.sql")){sql=new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);}if(scalar(c,"SELECT to_regclass(?)",schema+".execution_schema")==null){for(String part:sql.split(";"))if(!part.isBlank())execute(c,part);execute(c,"INSERT INTO execution_schema VALUES(1,?)",hash(sql));}else if(!hash(sql).equals(scalar(c,"SELECT checksum FROM execution_schema WHERE version=1")))throw new IllegalStateException("Execution migration checksum changed");return null;});}catch(Exception e){throw new IllegalStateException("Execution database unavailable",e);}}
    /** Runs the supplied database action in a transaction, committing success and rolling back failure. */
    public <T>T tx(com.lending.engine.bureau.infrastructure.BureauDatabase.Action<T> action){try(var c=pool.getConnection()){c.setAutoCommit(false);try{T result=action.run(c);c.commit();return result;}catch(Exception e){c.rollback();if(e instanceof RuntimeException r)throw r;throw new IllegalStateException("Execution transaction failed",e);}}catch(SQLException e){throw new IllegalStateException("Execution database unavailable",e);}}
    /** Derives the stable package identifier from the customer and campaign. */
    public static String id(String customer,String campaign){return "PK-"+hash(List.of(customer,campaign)).substring(0,60);}
    /** Acquires the database lock required to serialize related writes. */
    private void lock(Connection c,String customer)throws Exception{execute(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))",schema+":"+customer);}
    /** Returns the durable repository recovery position. */
    public long cursor(){return tx(c->{String value=scalar(c,"SELECT value FROM execution_state WHERE key='repository'");return value==null?0:Long.parseLong(value);});}
    /** Queues repository changes for campaign execution and advances the recovery cursor atomically. */
    public void admit(JsonNode page,long after){tx(c->{long last=after;for(var event:page.path("items")){StorageContract.validate(event);long sequence=event.path("sequence").asLong();if(sequence<=last)throw new Problem(503,"Non-monotonic offer repository recovery page");last=sequence;var offer=event.path("offer");String customer=offer.path("customerId").asText(),campaign=offer.path("qualification").path("campaignId").asText();if(!campaign.matches("[A-Za-z0-9_.:-]{1,80}"))throw new Problem(422,"Campaign reference required");execute(c,"INSERT INTO execution_tasks(id,customer_id,campaign_id) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET due=least(execution_tasks.due,now())",id(customer,campaign),customer,campaign);}if(last>after)execute(c,"INSERT INTO execution_state VALUES('repository',?) ON CONFLICT(key) DO UPDATE SET value=greatest(execution_state.value,excluded.value)",last);return null;});}
    /** Returns a bounded batch of campaign execution tasks ready for processing. */
    public List<Task> due(){return tx(c->{var rows=new ArrayList<Task>();try(var p=statement(c,"SELECT id,customer_id,campaign_id FROM execution_tasks WHERE due<=now() ORDER BY due,id LIMIT 20");var r=p.executeQuery()){while(r.next())rows.add(new Task(r.getString(1),r.getString(2),r.getString(3)));}return rows;});}
    /** Moves a pending execution task to its next retry time. */
    public void reschedule(String id,String state,String reason,int seconds){tx(c->{execute(c,"UPDATE execution_tasks SET state=?,reason=?,due=now()+(? * interval '1 second') WHERE id=?",state,reason,seconds,id);return null;});}
    /** Loads the current package for an identifier or customer/campaign pair. */
    private Package get(Connection c,String id)throws Exception{return one(c,"SELECT payload FROM execution_packages WHERE id=?",Package.class,id);}
    /** Loads the current package for an identifier or customer/campaign pair. */
    public Package get(String id){return tx(c->{var p=get(c,id);if(p==null)throw new Problem(404,"Execution package not found");return p;});}
    /** Computes the content identity used to detect changed execution packages. */
    private static String fingerprint(Package value){var node=(ObjectNode)Json.MAPPER.valueToTree(value);node.remove(List.of("version","createdAt","status","reason"));return hash(node);}
    /** Persists a package revision without erasing prior dispatch allocations. */
    private void save(Connection c,Package p,String fp)throws Exception{execute(c,"INSERT INTO execution_packages(id,customer_id,campaign_id,version,fingerprint,status,payload) VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET version=excluded.version,fingerprint=excluded.fingerprint,status=excluded.status,file_version=0,payload=excluded.payload",p.id(),p.customerId(),p.campaignId(),p.version(),fp,p.status(),Json.write(p));execute(c,"INSERT INTO execution_history(package_id,version,payload) VALUES(?,?,?)",p.id(),p.version(),Json.write(p));}
    /** Reserves or reuses a dispatch allocation before outbound file creation. */
    public Package prepare(Package draft,Policy policy,Instant now,Function<History,String> qualify){return tx(c->{lock(c,draft.customerId());var prior=get(c,draft.id());boolean same=scalar(c,"SELECT source_key FROM execution_dispatches WHERE source_key=?",draft.sourceKey())!=null;String latest=scalar(c,"SELECT max(created_at) FROM execution_dispatches WHERE customer_id=?",draft.customerId());long elapsed=9999999999L;if(latest!=null){try(var p=statement(c,"SELECT max(created_at) FROM execution_dispatches WHERE customer_id=?",draft.customerId());var r=p.executeQuery()){r.next();elapsed=Math.max(0,Duration.between(r.getTimestamp(1).toInstant(),now).getSeconds());}}int count=Integer.parseInt(scalar(c,"SELECT count(*) FROM execution_dispatches WHERE customer_id=? AND created_at>?",draft.customerId(),Timestamp.from(now.minus(Duration.ofDays(policy.windowDays())))));String reason=qualify.apply(new History(same,elapsed,count));if(!reason.equals("APPROVED")){invalidate(c,prior,reason);return prior==null?null:get(c,prior.id());}String fp=fingerprint(draft);if(prior!=null&&prior.status().equals("ACTIVE")&&fp.equals(scalar(c,"SELECT fingerprint FROM execution_packages WHERE id=?",draft.id())))return prior;var ready=draft.state(prior==null?1:prior.version()+1,"ACTIVE",null);save(c,ready,fp);execute(c,"INSERT INTO execution_dispatches(source_key,package_id,customer_id,created_at) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",ready.sourceKey(),ready.id(),ready.customerId(),Timestamp.from(now));return ready;});}
    /** Withdraws an existing package while preserving its export and allocation history. */
    private void invalidate(Connection c,Package old,String reason)throws Exception{if(old!=null&&(!old.status().equals("WITHDRAWN")||!Objects.equals(old.reason(),reason)))save(c,old.state(old.version()+1,"WITHDRAWN",reason),fingerprint(old));}
    /** Withdraws an existing package while preserving its export and allocation history. */
    public Package invalidate(String id,String reason){return tx(c->{var old=get(c,id);if(old!=null){lock(c,old.customerId());old=get(c,id);invalidate(c,old,reason);}return old==null?null:get(c,id);});}
    /** Defines the publisher boundary used by application workflows. */
    @FunctionalInterface public interface Publisher {
        /** Persists a changed package and its immutable history within the current transaction. */
        void write(Package value)throws Exception;}
    /** Commits package publication while serializing customer-level dispatch limits. */
    public void publish(Package expected,Publisher writer){tx(c->{lock(c,expected.customerId());var current=get(c,expected.id());if(current==null||current.version()!=expected.version())return null;writer.write(current);execute(c,"UPDATE execution_packages SET file_version=? WHERE id=?",current.version(),current.id());if(current.status().equals("ACTIVE"))execute(c,"UPDATE execution_dispatches SET exported_at=coalesce(exported_at,now()) WHERE source_key=?",current.sourceKey());return null;});}
    /** Marks an execution package as published after its outbound file bundle is verified. */
    public boolean filesReady(Package p){return tx(c->Long.toString(p.version()).equals(scalar(c,"SELECT file_version FROM execution_packages WHERE id=? AND version=?",p.id(),p.version())));}
    /** Lists campaign packages using bounded operational filters. */
    public Object list(String customer,String after,int limit){if(limit<1||limit>100)throw new Problem(422,"limit must be 1-100");return tx(c->{var rows=new ArrayList<JsonNode>();try(var p=statement(c,"SELECT payload FROM execution_packages WHERE id>?"+(customer==null?"":" AND customer_id=?")+" ORDER BY id LIMIT ?",customer==null?new Object[]{after==null?"":after,limit}:new Object[]{after==null?"":after,customer,limit});var r=p.executeQuery()){while(r.next())rows.add(Json.MAPPER.readTree(r.getString(1)));}var result=new LinkedHashMap<String,Object>();result.put("items",rows);result.put("nextAfter",rows.size()==limit?rows.get(rows.size()-1).path("id").asText():null);return result;});}
    /** Returns immutable campaign package revisions. */
    public Object history(String id,long after){if(after<0)throw new Problem(422,"after must be non-negative");return tx(c->{var rows=new ArrayList<JsonNode>();try(var p=statement(c,"SELECT payload FROM execution_history WHERE package_id=? AND version>? ORDER BY version LIMIT 50",id,after);var r=p.executeQuery()){while(r.next())rows.add(Json.MAPPER.readTree(r.getString(1)));}return rows;});}
    /** Returns the campaign execution tasks available for operational inspection. */
    public Object tasks(String customer){return tx(c->{var rows=new ArrayList<Object>();try(var p=statement(c,"SELECT id,customer_id,campaign_id,state,reason FROM execution_tasks"+(customer==null?"":" WHERE customer_id=?")+" ORDER BY due,id LIMIT 100",customer==null?new Object[]{}:new Object[]{customer});var r=p.executeQuery()){while(r.next()){var row=new LinkedHashMap<String,Object>();row.put("id",r.getString(1));row.put("customerId",r.getString(2));row.put("campaignId",r.getString(3));row.put("state",r.getString(4));row.put("reason",r.getString(5));rows.add(row);}}return rows;});}
    /** Reports component progress and availability for operational health checks. */
    public Map<String,Object> health(){return tx(c->new LinkedHashMap<>(Map.of("schema",schema,"packages",Long.parseLong(scalar(c,"SELECT count(*) FROM execution_packages")),"exportedDispatches",Long.parseLong(scalar(c,"SELECT count(*) FROM execution_dispatches WHERE exported_at IS NOT NULL")),"pendingFiles",Long.parseLong(scalar(c,"SELECT count(*) FROM execution_packages WHERE file_version<>version")))));}
    /** Releases the resources owned by this component. */
    public void close(){pool.close();}
}
