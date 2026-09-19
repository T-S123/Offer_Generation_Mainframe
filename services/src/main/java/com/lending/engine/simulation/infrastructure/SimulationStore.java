/**
 * Separate H2 database holds immutable experiments and paged result rows; it has no active customer or
 * pipeline tables.
 */
package com.lending.engine.simulation.infrastructure;
import com.lending.engine.simulation.domain.Simulation.*;
import com.lending.engine.domain.Model.Problem;
import com.lending.engine.infrastructure.Json;
import java.sql.*;
import java.util.*;

/**
 * Separate H2 database holds immutable experiments and paged result rows; it has no active customer or
 * pipeline tables.
 */
public final class SimulationStore implements AutoCloseable {
    private final Connection db;
    /** Initializes simulation store with the supplied configuration and dependencies. */
    public SimulationStore(String url){try{db=DriverManager.getConnection(url,"sa","");try(var s=db.createStatement()){s.execute("CREATE TABLE IF NOT EXISTS experiments(kind VARCHAR(20),id VARCHAR(80),version INT,payload CLOB NOT NULL,PRIMARY KEY(kind,id,version))");s.execute("CREATE TABLE IF NOT EXISTS result_rows(run_id VARCHAR(80),ordinal INT,payload CLOB NOT NULL,PRIMARY KEY(run_id,ordinal))");}for(var r:list("RUN",Run.class))if(r.status().equals("QUEUED")||r.status().equals("RUNNING"))put("RUN",r.id(),1,new Run(r.id(),r.request(),r.draft(),"FAILED",r.createdAt(),null,"Interrupted by restart; submit a new run",null),true);}catch(SQLException e){throw new IllegalStateException("Cannot open Business simulation database",e);}}
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
    /** Releases the resources owned by this component. */
    public synchronized void close(){try{db.close();}catch(SQLException e){throw new IllegalStateException(e);}}
}
