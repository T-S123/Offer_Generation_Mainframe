/**
 * Independently owned customer/warehouse schema. Stores every valid revision and advances current state
 * monotonically in the same transaction.
 */
package com.lending.engine.storage;
import com.lending.engine.infrastructure.Json;
import com.lending.engine.domain.Model.Problem;
import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.*;
import static com.lending.engine.bureau.infrastructure.BureauDatabase.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/**
 * Independently owned customer/warehouse schema. Stores every valid revision and advances current state
 * monotonically in the same transaction.
 */
public final class OfferCopyStore implements AutoCloseable {
    public final String schema;private final HikariDataSource pool;
    /** Initializes offer copy store with the supplied configuration and dependencies. */
    public OfferCopyStore(String url,String schema){this.schema=schema;if(!schema.matches("[a-z][a-z0-9_]{0,62}")||schema.equals("public")||schema.equals("marketing_offers"))throw new IllegalArgumentException("Independent copy schema required");try{var uri=URI.create(url);String[] auth=uri.getRawUserInfo().split(":",2);var config=new HikariConfig();config.setJdbcUrl("jdbc:postgresql://"+uri.getHost()+":"+(uri.getPort()<0?5432:uri.getPort())+uri.getRawPath());config.setUsername(URLDecoder.decode(auth[0],StandardCharsets.UTF_8));config.setPassword(URLDecoder.decode(auth[1],StandardCharsets.UTF_8));try(var c=DriverManager.getConnection(config.getJdbcUrl(),config.getUsername(),config.getPassword())){execute(c,"CREATE SCHEMA IF NOT EXISTS "+schema);}config.setConnectionInitSql("SET search_path TO "+schema);config.setMaximumPoolSize(4);config.setConnectionTimeout(5000);config.addDataSourceProperty("connectTimeout","5");config.addDataSourceProperty("socketTimeout","20");config.setPoolName(schema);pool=new HikariDataSource(config);
        tx(c->{execute(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))",schema+".migration");String sql;try(var in=getClass().getResourceAsStream("/db/storage/V001__offer_copies.sql")){sql=new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);}String exists=scalar(c,"SELECT to_regclass(?)",schema+".copy_schema");if(exists==null){for(String part:sql.split(";"))if(!part.isBlank())execute(c,part);execute(c,"INSERT INTO copy_schema VALUES(1,?)",hash(sql));}else if(!hash(sql).equals(scalar(c,"SELECT checksum FROM copy_schema WHERE version=1")))throw new IllegalStateException("Copy migration checksum changed");return null;});
    }catch(Exception e){throw new IllegalStateException("Offer-copy database unavailable",e);}}
    /** Runs the supplied database action in a transaction, committing success and rolling back failure. */
    public <T>T tx(com.lending.engine.bureau.infrastructure.BureauDatabase.Action<T> action){try(var c=pool.getConnection()){c.setAutoCommit(false);try{T result=action.run(c);c.commit();return result;}catch(Exception e){c.rollback();if(e instanceof RuntimeException r)throw r;throw new IllegalStateException(e);}}catch(SQLException e){throw new IllegalStateException("Offer-copy transaction failed",e);}}
    /** Stores each valid event revision and advances current state monotonically in one transaction. */
    public void receive(JsonNode event){StorageContract.validate(event);var offer=event.path("offer");String id=offer.path("id").asText(),eid=event.path("eventId").asText();long version=offer.path("version").asLong(),sequence=event.path("sequence").asLong();String fp=hash(event);
        tx(c->{execute(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))",schema+":"+id);String seen=scalar(c,"SELECT fingerprint FROM offer_events WHERE event_id=? OR (offer_id=? AND version=?) OR source_sequence=?",eid,id,version,sequence);if(seen!=null){if(!seen.equals(fp))throw new Problem(409,"Conflicting storage event identity/version/sequence");return null;}execute(c,"INSERT INTO offer_events(event_id,offer_id,version,source_sequence,fingerprint,payload) VALUES(?,?,?,?,?,?)",eid,id,version,sequence,fp,Json.write(event));execute(c,"INSERT INTO current_offers(id,customer_id,version,event_id,payload) VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET customer_id=excluded.customer_id,version=excluded.version,event_id=excluded.event_id,payload=excluded.payload,copied_at=now() WHERE current_offers.version<excluded.version",id,offer.path("customerId").asText(),version,eid,Json.write(event));return null;});}
    /** Records an invalid or conflicting event in quarantine for inspection. */
    public void reject(String id,String payload){tx(c->{execute(c,"INSERT INTO rejected_events(id,fingerprint,reason) VALUES(?,?,'Contract or identity conflict; inspect source') ON CONFLICT DO NOTHING",id,hash(payload));return null;});}
    /** Reads or advances the durable HTTP recovery cursor without allowing rollback. */
    public long cursor(){return tx(c->{String v=scalar(c,"SELECT value FROM copy_state WHERE key='recovery'");return v==null?0:Long.parseLong(v);});}
    /** Reads or advances the durable HTTP recovery cursor without allowing rollback. */
    public void cursor(long value){tx(c->{execute(c,"INSERT INTO copy_state VALUES('recovery',?) ON CONFLICT(key) DO UPDATE SET value=greatest(copy_state.value,excluded.value)",value);return null;});}
    /** Returns a filtered page of current copied offers. */
    public Map<String,Object> page(String customer,String after,int limit){if(limit<1||limit>100)throw new Problem(422,"limit must be 1-100");return tx(c->{var rows=new ArrayList<JsonNode>();try(var p=statement(c,"SELECT payload FROM current_offers WHERE id>?"+(customer==null?"":" AND customer_id=?")+" ORDER BY id LIMIT ?",customer==null?new Object[]{after==null?"":after,limit}:new Object[]{after==null?"":after,customer,limit});var r=p.executeQuery()){while(r.next())rows.add(Json.MAPPER.readTree(r.getString(1)));}var page=new LinkedHashMap<String,Object>();page.put("items",rows);page.put("nextAfter",rows.size()==limit?rows.get(rows.size()-1).path("offer").path("id").asText():null);return page;});}
    /** Retrieves the current copied snapshot for an offer family. */
    public JsonNode get(String id){return tx(c->{String value=scalar(c,"SELECT payload FROM current_offers WHERE id=?",id);if(value==null)throw new Problem(404,"Offer copy not found");return Json.MAPPER.readTree(value);});}
    /** Returns all retained revisions of a copied offer family. */
    public List<JsonNode> history(String id,long after){if(after<0)throw new Problem(422,"after must be non-negative");return tx(c->{var result=new ArrayList<JsonNode>();try(var p=statement(c,"SELECT payload FROM offer_events WHERE offer_id=? AND version>? ORDER BY version LIMIT 50",id,after);var r=p.executeQuery()){while(r.next())result.add(Json.MAPPER.readTree(r.getString(1)));}return result;});}
    /** Reports component progress and availability for operational health checks. */
    public Map<String,Object> health(){return tx(c->{var result=new LinkedHashMap<String,Object>();result.put("schema",schema);result.put("families",Long.parseLong(scalar(c,"SELECT count(*) FROM current_offers")));result.put("historyEvents",Long.parseLong(scalar(c,"SELECT count(*) FROM offer_events")));result.put("rejected",Long.parseLong(scalar(c,"SELECT count(*) FROM rejected_events")));String cursor=scalar(c,"SELECT value FROM copy_state WHERE key='recovery'");result.put("recoveryAfter",cursor==null?0:Long.parseLong(cursor));return result;});}
    /** Releases the resources owned by this component. */
    public void close(){pool.close();}
}
