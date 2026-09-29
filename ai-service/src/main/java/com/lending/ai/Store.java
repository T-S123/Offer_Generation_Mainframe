package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static com.lending.ai.Json.*;

/** JDBC state, immutable artifacts and atomic budget reservations; each service owns a separate schema. */
public final class Store implements AutoCloseable {
    private final Connection db;
    public record Saved(String id,int version,JsonNode data) {}
    public Store(String url,String user,String password,String schema) {
        require(schema.matches("[a-z][a-z0-9_]{0,50}"),"INVALID_SCHEMA","Invalid schema");
        Connection opened=null;
        try {
            opened=DriverManager.getConnection(url,user,password);db=opened;
            try(var s=db.createStatement()){
                if(db.getMetaData().getDatabaseProductName().equals("PostgreSQL")){
                    try(var lock=db.prepareStatement("SELECT pg_try_advisory_lock(?)")){
                        lock.setLong(1,Long.parseUnsignedLong(hash(schema).substring(0,15),16));
                        try(var r=lock.executeQuery()){r.next();if(!r.getBoolean(1))throw new IllegalStateException("Another service process owns schema "+schema);}
                    }
                }
                s.execute("CREATE SCHEMA IF NOT EXISTS "+schema);db.setSchema(schema);Migration.apply(db);
            }
        }catch(SQLException|RuntimeException e){if(opened!=null)try{opened.close();}catch(SQLException ignored){}throw new IllegalStateException("Cannot open AI store",e);}
    }
    public synchronized Saved get(String kind,String id) {
        try(var p=db.prepareStatement("SELECT version,payload FROM records WHERE kind=? AND id=?")){
            p.setString(1,kind);p.setString(2,id);try(var r=p.executeQuery()){if(!r.next())throw new Fault(404,"NOT_FOUND",kind+" not found");return new Saved(id,r.getInt(1),read(r.getString(2)));}}
        catch(SQLException e){throw new IllegalStateException(e);}
    }
    public synchronized Optional<Saved> find(String kind,String id){try{return Optional.of(get(kind,id));}catch(Fault e){if(e.status==404)return Optional.empty();throw e;}}
    public synchronized Saved create(String kind,String id,Object value){
        try(var p=db.prepareStatement("INSERT INTO records VALUES(?,?,1,?)")){
            p.setString(1,kind);p.setString(2,id);p.setString(3,write(value));p.executeUpdate();return new Saved(id,1,tree(value));
        }catch(SQLException e){if("23505".equals(e.getSQLState()))throw new Fault(409,"DUPLICATE","Resource already exists");throw new IllegalStateException(e);}
    }
    /** Uses an optimistic version check across processes; stale workers cannot overwrite newer state. */
    public synchronized Saved update(String kind,String id,int expected,Object value){
        try(var p=db.prepareStatement("UPDATE records SET version=version+1,payload=? WHERE kind=? AND id=? AND version=?")){
            p.setString(1,write(value));p.setString(2,kind);p.setString(3,id);p.setInt(4,expected);
            if(p.executeUpdate()!=1)throw new Fault(409,"STALE_VERSION","Reload current state");
            return new Saved(id,expected+1,tree(value));
        }catch(SQLException e){throw new IllegalStateException(e);}
    }
    /** Commits conversation state and its replay receipt atomically after an idempotent external action. */
    public synchronized void updateWithReceipt(String kind,String id,int version,Object value,String receiptKind,String receiptId,Object receipt){
        try{
            db.setAutoCommit(false);update(kind,id,version,value);create(receiptKind,receiptId,receipt);db.commit();
        }catch(RuntimeException|SQLException e){try{db.rollback();}catch(SQLException ignored){}if(e instanceof RuntimeException r)throw r;throw new IllegalStateException(e);}
        finally{try{db.setAutoCommit(true);}catch(SQLException e){throw new IllegalStateException(e);}}
    }
    public synchronized List<Saved> list(String kind,String after,int limit){
        require(limit>0&&limit<=100,"PAGE_LIMIT","Use limit 1-100");
        try(var p=db.prepareStatement("SELECT id,version,payload FROM records WHERE kind=? AND id>? ORDER BY id LIMIT ?")){
            p.setString(1,kind);p.setString(2,after==null?"":after);p.setInt(3,limit);
            try(var r=p.executeQuery()){var out=new ArrayList<Saved>();while(r.next())out.add(new Saved(r.getString(1),r.getInt(2),read(r.getString(3))));return out;}
        }catch(SQLException e){throw new IllegalStateException(e);}
    }
    public synchronized String artifact(Object value){
        String hash=hash(value),id="sha256:"+hash;
        try(var p=db.prepareStatement("INSERT INTO artifacts VALUES(?,?,?)")){
            p.setString(1,id);p.setString(2,hash);p.setString(3,write(value));p.executeUpdate();
        }catch(SQLException e){if(!"23505".equals(e.getSQLState()))throw new IllegalStateException(e);}
        return id;
    }
    public synchronized JsonNode artifact(String id){
        try(var p=db.prepareStatement("SELECT hash,payload FROM artifacts WHERE id=?")){
            p.setString(1,id);try(var r=p.executeQuery()){
                if(!r.next())throw new Fault(404,"NOT_FOUND","Artifact not found");var n=read(r.getString(2));
                if(!hash(n).equals(r.getString(1)))throw new Fault(503,"CORRUPT_ARTIFACT","Artifact integrity check failed");return n;
            }
        }catch(SQLException e){throw new IllegalStateException(e);}
    }
    public record Budget(int maxEvaluations,int maxModelCalls,long maxTokens,BigDecimal maxCostUsd,String deadline,int exploration,int refinement,int validation,int minimumEligible,double eligibilityFloor,double acceptanceFloor) {
        public void validate(){
            require(maxEvaluations>=4&&maxEvaluations<=1000&&maxModelCalls>=6&&maxModelCalls<=500&&maxTokens>=1000&&maxTokens<=10000000&&maxCostUsd!=null&&maxCostUsd.signum()>0,"BUDGET_INVALID","Invalid resource budget");
            require(exploration>=1&&refinement>=0&&validation>=2&&1+exploration+refinement+validation<=maxEvaluations,"BUDGET_INVALID","Invalid phase allocation");
            require(minimumEligible>=1&&eligibilityFloor>=0&&eligibilityFloor<=100&&acceptanceFloor>=0&&acceptanceFloor<=100,"BUDGET_INVALID","Invalid metric support/floors");
            require(Instant.parse(deadline).isAfter(Instant.now()),"DEADLINE","Deadline has passed");
        }
    }
    public synchronized void budget(String id,Budget value){
        value.validate();
        try(var p=db.prepareStatement("INSERT INTO budgets VALUES(?,?,0,0,0,0)")){p.setString(1,id);p.setString(2,write(value));p.executeUpdate();}
        catch(SQLException e){if(!"23505".equals(e.getSQLState()))throw new IllegalStateException(e);
            var old=budget(id);require(hash(old.path("limits")).equals(hash(value)),"BUDGET_CONFLICT","Budget already exists with different limits");}
    }
    /** Analyst-authorized extensions retain every spent reservation and the original phase allocation. */
    public synchronized void extend(String id,Budget value){
        value.validate();var old=convert(budget(id).path("limits"),Budget.class);if(hash(value).equals(hash(old)))return;
        require(value.maxEvaluations()>=old.maxEvaluations()&&value.maxModelCalls()>=old.maxModelCalls()&&value.maxTokens()>=old.maxTokens()&&value.maxCostUsd().compareTo(old.maxCostUsd())>=0
            &&Instant.parse(value.deadline()).isAfter(Instant.parse(old.deadline())),"BUDGET_EXTENSION","Only increasing limits and deadline are permitted");
        require(value.exploration()==old.exploration()&&value.refinement()==old.refinement()&&value.validation()==old.validation()&&value.minimumEligible()==old.minimumEligible()
            &&value.eligibilityFloor()==old.eligibilityFloor()&&value.acceptanceFloor()==old.acceptanceFloor(),"SCOPE_VIOLATION","Budget extension cannot change search phases or ranking criteria");
        try(var q=db.prepareStatement("UPDATE budgets SET payload=? WHERE id=?")){q.setString(1,write(value));q.setString(2,id);q.executeUpdate();}catch(SQLException e){throw new IllegalStateException(e);}
    }
    /** Reserves worst-case spend once per operation before dispatch; uncertain outcomes retain the reservation. */
    public synchronized boolean reserve(String workflow,String request,int evaluations,int calls,long tokens,BigDecimal cost){
        require(evaluations>=0&&calls>=0&&tokens>=0&&cost!=null&&cost.signum()>=0,"INVALID_RESERVATION","Nonnegative reservation required");
        var fingerprint=hash(List.of(evaluations,calls,tokens,cost));
        try{
            db.setAutoCommit(false);
            Budget limit;int usedE,usedC;long usedT;BigDecimal usedCost;
            try(var p=db.prepareStatement("SELECT payload,evaluations,calls,tokens,cost FROM budgets WHERE id=? FOR UPDATE")){
                p.setString(1,workflow);try(var r=p.executeQuery()){if(!r.next())throw new Fault(404,"BUDGET_MISSING","Workflow budget missing");
                    limit=convert(read(r.getString(1)),Budget.class);usedE=r.getInt(2);usedC=r.getInt(3);usedT=r.getLong(4);usedCost=r.getBigDecimal(5);}}
            try(var p=db.prepareStatement("SELECT hash FROM reservations WHERE workflow_id=? AND id=?")){
                p.setString(1,workflow);p.setString(2,request);try(var r=p.executeQuery()){if(r.next()){require(r.getString(1).equals(fingerprint),"RESERVATION_CONFLICT","Reservation identity changed");db.commit();return false;}}}
            if(!Instant.now().isBefore(Instant.parse(limit.deadline())))throw new Fault(409,"DEADLINE","Workflow deadline reached");
            if(usedE+evaluations>limit.maxEvaluations()||usedC+calls>limit.maxModelCalls()||usedT+tokens>limit.maxTokens()||usedCost.add(cost).compareTo(limit.maxCostUsd())>0)
                throw new Fault(409,"BUDGET_EXHAUSTED","Approved resource budget exhausted");
            try(var p=db.prepareStatement("UPDATE budgets SET evaluations=?,calls=?,tokens=?,cost=? WHERE id=?")){
                p.setInt(1,usedE+evaluations);p.setInt(2,usedC+calls);p.setLong(3,usedT+tokens);p.setBigDecimal(4,usedCost.add(cost));p.setString(5,workflow);p.executeUpdate();}
            try(var p=db.prepareStatement("INSERT INTO reservations VALUES(?,?,?)")){p.setString(1,workflow);p.setString(2,request);p.setString(3,fingerprint);p.executeUpdate();}
            try(var p=db.prepareStatement("INSERT INTO reservation_values VALUES(?,?,?,?)")){p.setString(1,workflow);p.setString(2,request);p.setLong(3,tokens);p.setBigDecimal(4,cost);p.executeUpdate();}
            db.commit();return true;
        }catch(RuntimeException|SQLException e){try{db.rollback();}catch(SQLException ignored){}if(e instanceof RuntimeException r)throw r;throw new IllegalStateException(e);}
        finally{try{db.setAutoCommit(true);}catch(SQLException e){throw new IllegalStateException(e);}}
    }
    /** Settles provider-reported token usage once, keeping uncertain calls at their reserved maximum. */
    public synchronized void settle(String workflow,String request,long actualTokens){
        require(actualTokens>0,"USAGE_UNAVAILABLE","Provider usage must be positive");
        try{
            db.setAutoCommit(false);
            try(var p=db.prepareStatement("SELECT id FROM budgets WHERE id=? FOR UPDATE")){p.setString(1,workflow);try(var r=p.executeQuery()){require(r.next(),"BUDGET_MISSING","Budget not found");}}
            try(var p=db.prepareStatement("SELECT tokens FROM settlements WHERE workflow_id=? AND id=?")){p.setString(1,workflow);p.setString(2,request);try(var r=p.executeQuery()){if(r.next()){require(r.getLong(1)==actualTokens,"USAGE_CONFLICT","Settled usage cannot change");db.commit();return;}}}
            long reserved;BigDecimal cost;
            try(var p=db.prepareStatement("SELECT tokens,cost FROM reservation_values WHERE workflow_id=? AND id=?")){p.setString(1,workflow);p.setString(2,request);try(var r=p.executeQuery()){require(r.next(),"RESERVATION_MISSING","Usage requires its original reservation");reserved=r.getLong(1);cost=r.getBigDecimal(2);}}
            require(actualTokens<=reserved,"RESERVATION_UNDERESTIMATED","Provider usage exceeded the conservative reservation");
            BigDecimal charged=cost.multiply(BigDecimal.valueOf(actualTokens)).divide(BigDecimal.valueOf(reserved),8,java.math.RoundingMode.CEILING);
            try(var p=db.prepareStatement("UPDATE budgets SET tokens=tokens-?,cost=cost-? WHERE id=?")){p.setLong(1,reserved-actualTokens);p.setBigDecimal(2,cost.subtract(charged));p.setString(3,workflow);p.executeUpdate();}
            try(var p=db.prepareStatement("INSERT INTO settlements VALUES(?,?,?,?)")){p.setString(1,workflow);p.setString(2,request);p.setLong(3,actualTokens);p.setBigDecimal(4,charged);p.executeUpdate();}
            db.commit();
        }catch(RuntimeException|SQLException e){try{db.rollback();}catch(SQLException ignored){}if(e instanceof RuntimeException r)throw r;throw new IllegalStateException(e);}
        finally{try{db.setAutoCommit(true);}catch(SQLException e){throw new IllegalStateException(e);}}
    }
    public synchronized JsonNode budget(String id){
        try(var p=db.prepareStatement("SELECT payload,evaluations,calls,tokens,cost FROM budgets WHERE id=?")){
            p.setString(1,id);try(var r=p.executeQuery()){if(!r.next())throw new Fault(404,"NOT_FOUND","Budget not found");
                return obj("limits",read(r.getString(1)),"evaluations",r.getInt(2),"modelCalls",r.getInt(3),"reservedTokens",r.getLong(4),"reservedCostUsd",r.getBigDecimal(5));}}
        catch(SQLException e){throw new IllegalStateException(e);}
    }
    public synchronized void close(){try{db.close();}catch(SQLException e){throw new IllegalStateException(e);}}
}
