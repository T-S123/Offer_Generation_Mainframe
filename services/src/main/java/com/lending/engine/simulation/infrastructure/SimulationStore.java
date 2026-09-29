/**
 * Separate H2 database holds immutable experiments and paged result rows; it has no active customer or
 * pipeline tables. Request admissions commit atomically with their generated resources.
 */
package com.lending.engine.simulation.infrastructure;
import com.lending.engine.simulation.domain.Simulation.*;
import com.lending.engine.domain.Model.Problem;
import com.lending.engine.infrastructure.Json;
import java.sql.*;
import java.util.*;

/**
 * Separate H2 database holds immutable experiments and paged result rows; it has no active customer or
 * pipeline tables. Request admissions commit atomically with their generated resources.
 */
public final class SimulationStore implements AutoCloseable {
    private final Connection db;
    /** Initializes simulation store with the supplied configuration and dependencies. */
    public SimulationStore(String url){try{db=DriverManager.getConnection(url,"sa","");try(var s=db.createStatement()){s.execute("CREATE TABLE IF NOT EXISTS experiments(kind VARCHAR(20),id VARCHAR(80),version INT,payload CLOB NOT NULL,PRIMARY KEY(kind,id,version))");s.execute("CREATE TABLE IF NOT EXISTS simulation_admissions(operation VARCHAR(20),request_key VARCHAR(120),request_hash VARCHAR(64) NOT NULL,resource_id VARCHAR(80) NOT NULL,PRIMARY KEY(operation,request_key))");s.execute("CREATE TABLE IF NOT EXISTS result_rows(run_id VARCHAR(80),ordinal INT,payload CLOB NOT NULL,PRIMARY KEY(run_id,ordinal))");}for(var r:list("RUN",Run.class))if(r.status().equals("QUEUED")||r.status().equals("RUNNING"))put("RUN",r.id(),1,new Run(r.id(),r.request(),r.draft(),"FAILED",r.createdAt(),null,"Interrupted by restart; submit a new run",null),true);}catch(SQLException e){throw new IllegalStateException("Cannot open Business simulation database",e);}}
    /** Loads a saved simulation object by its category and identifier. */
    public synchronized <T>T get(String kind,String id,int version,Class<T> type){try(var p=db.prepareStatement("SELECT payload FROM experiments WHERE kind=? AND id=? AND (?=0 OR version=?) ORDER BY version DESC LIMIT 1")){p.setString(1,kind);p.setString(2,id);p.setInt(3,version);p.setInt(4,version);try(var r=p.executeQuery()){if(!r.next())throw new Problem(404,"Business "+kind.toLowerCase()+" not found");return Json.read(r.getString(1),type);}}catch(SQLException e){throw new IllegalStateException(e);}}
    /** Lists saved simulation objects in the requested category. */
    public synchronized <T>List<T> list(String kind,Class<T> type){try(var p=db.prepareStatement("SELECT e.payload FROM experiments e WHERE kind=? AND version=(SELECT MAX(x.version) FROM experiments x WHERE x.kind=e.kind AND x.id=e.id) ORDER BY id DESC")){p.setString(1,kind);try(var r=p.executeQuery()){var out=new ArrayList<T>();while(r.next())out.add(Json.read(r.getString(1),type));return List.copyOf(out);}}catch(SQLException e){throw new IllegalStateException(e);}}
    /** Persists a versioned simulation object in the isolated database. */
    public synchronized void put(String kind,String id,int version,Object data,boolean replace){try(var p=db.prepareStatement((replace?"MERGE INTO experiments KEY(kind,id,version)":"INSERT INTO experiments")+" VALUES(?,?,?,?)")){p.setString(1,kind);p.setString(2,id);p.setInt(3,version);p.setString(4,Json.write(data));p.executeUpdate();}catch(SQLException e){throw new IllegalStateException(e);}}
    /** Commits experiment completion and its detailed comparison rows atomically. */
    public synchronized void complete(Run run,List<Row> rows){try{db.setAutoCommit(false);try(var p=db.prepareStatement("INSERT INTO result_rows VALUES(?,?,?)")){for(int i=0;i<rows.size();i++){p.setString(1,run.id());p.setInt(2,i);p.setString(3,Json.write(rows.get(i)));p.addBatch();}p.executeBatch();}put("RUN",run.id(),1,run,true);db.commit();}catch(Exception e){try{db.rollback();}catch(SQLException ignored){}throw new IllegalStateException(e);}finally{try{db.setAutoCommit(true);}catch(SQLException e){throw new IllegalStateException(e);}}}
    /** Returns a bounded page of persisted experiment comparison rows. */
    public synchronized List<Row> rows(String id,int offset,int limit){if(offset<0||limit<1||limit>100)throw new Problem(422,"Use offset >=0 and limit 1-100");try(var p=db.prepareStatement("SELECT payload FROM result_rows WHERE run_id=? ORDER BY ordinal LIMIT ? OFFSET ?")){p.setString(1,id);p.setInt(2,limit);p.setInt(3,offset);try(var r=p.executeQuery()){var out=new ArrayList<Row>();while(r.next())out.add(Json.read(r.getString(1),Row.class));return out;}}catch(SQLException e){throw new IllegalStateException(e);}}

    /** Returns whether an atomic resource admission reused an earlier request. */
    public record Admission<T>(T value, boolean replayed) {}
    /** Atomically creates a resource and its idempotency receipt, rejecting changed request bodies. */
    public synchronized <T> Admission<T> admit(String operation, String key, String hash, Class<T> type,
                                               java.util.function.Supplier<T> create, java.util.function.Function<T,String> id) {
        if (key == null) return new Admission<>(create.get(), false);
        if (!key.matches("[A-Za-z0-9._:-]{1,120}")) throw new Problem(422, "Invalid Idempotency-Key");
        try {
            try (var p = db.prepareStatement("SELECT request_hash,resource_id FROM simulation_admissions WHERE operation=? AND request_key=?")) {
                p.setString(1, operation); p.setString(2, key);
                try (var r = p.executeQuery()) {
                    if (r.next()) {
                        if (!hash.equals(r.getString(1))) throw new Problem(409, "Idempotency-Key was used with a different request");
                        return new Admission<>(get(operation, r.getString(2), 1, type), true);
                    }
                }
            }
            db.setAutoCommit(false);
            T value = create.get();
            try (var p = db.prepareStatement("INSERT INTO simulation_admissions VALUES(?,?,?,?)")) {
                p.setString(1, operation); p.setString(2, key); p.setString(3, hash); p.setString(4, id.apply(value)); p.executeUpdate();
            }
            db.commit();
            return new Admission<>(value, false);
        } catch (RuntimeException | SQLException e) {
            try { db.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Cannot admit simulation request", e);
        } finally {
            try { db.setAutoCommit(true); } catch (SQLException e) { throw new IllegalStateException(e); }
        }
    }
    /** Resolves an admitted request after an uncertain HTTP response without admitting another resource. */
    public synchronized Object admission(String operation, String key) {
        if (!Set.of("DRAFT","RUN").contains(operation)) throw new Problem(422, "Unsupported admission operation");
        try (var p=db.prepareStatement("SELECT resource_id FROM simulation_admissions WHERE operation=? AND request_key=?")) {
            p.setString(1,operation); p.setString(2,key);
            try(var r=p.executeQuery()) {
                if(!r.next()) throw new Problem(404,"Admission not found");
                return Map.of("operation",operation,"requestKey",key,"resourceId",r.getString(1));
            }
        } catch(SQLException e) { throw new IllegalStateException(e); }
    }

    /** Releases the resources owned by this component. */
    public synchronized void close(){try{db.close();}catch(SQLException e){throw new IllegalStateException(e);}}
}
