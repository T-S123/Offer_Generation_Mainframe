/**
 * Pooled PostgreSQL transactions and ordered checksum migrations for bureau work, responses and
 * offer-variant qualifications.
 */
package com.lending.engine.bureau.infrastructure;

import com.zaxxer.hikari.*;
import com.lending.engine.infrastructure.Json;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/**
 * Pooled PostgreSQL transactions and ordered checksum migrations for bureau work, responses and
 * offer-variant qualifications.
 */
public final class BureauDatabase implements AutoCloseable {
    private final HikariDataSource pool;
    /** Defines a database action executed within the owning transaction boundary. */
    @FunctionalInterface public interface Action<T> { 
        /** Runs the supplied action using the transaction connection. */
        T run(Connection c) throws Exception; }
    /** Initializes bureau database with the supplied configuration and dependencies. */
    public BureauDatabase(String url) {this(url,"public");}
    /** Initializes bureau database with the supplied configuration and dependencies. */
    public BureauDatabase(String url,String schema) {
        try {
            URI uri=URI.create(url);if(!Set.of("postgres","postgresql").contains(uri.getScheme()))throw new IllegalArgumentException();
            String[] credentials=uri.getRawUserInfo().split(":",2);
            HikariConfig config=new HikariConfig();
            if(!schema.matches("[a-z][a-z0-9_]{0,62}"))throw new IllegalArgumentException("Invalid schema name");
            if(!schema.equals("public"))config.setConnectionInitSql("SET search_path TO "+schema);
            config.setJdbcUrl("jdbc:postgresql://"+uri.getHost()+":"+(uri.getPort()<0?5432:uri.getPort())+uri.getRawPath());
            config.setUsername(URLDecoder.decode(credentials[0],StandardCharsets.UTF_8));
            config.setPassword(URLDecoder.decode(credentials[1],StandardCharsets.UTF_8));
            if(!schema.equals("public"))try(var initializer=DriverManager.getConnection(config.getJdbcUrl(),config.getUsername(),config.getPassword())){execute(initializer,"CREATE SCHEMA IF NOT EXISTS "+schema);}
            config.setMaximumPoolSize(Integer.getInteger("bureau.db.pool",12));config.setConnectionTimeout(5000);
            config.addDataSourceProperty("connectTimeout","5");config.addDataSourceProperty("socketTimeout","20");
            config.addDataSourceProperty("reWriteBatchedInserts","true");
            config.setPoolName("bureau");pool=new HikariDataSource(config);
            tx(c->{
                execute(c,"SELECT pg_advisory_xact_lock(73103001)");
                execute(c,"CREATE TABLE IF NOT EXISTS bureau_schema(version int PRIMARY KEY, checksum varchar(64) NOT NULL)");
                String[] migrations={"V001__bureau.sql","V002__decision_response.sql","V003__offer_variants.sql"};
                for(int i=0;i<migrations.length;i++){
                    String sql;try(var in=getClass().getResourceAsStream("/db/bureau/"+migrations[i])){sql=new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);}
                    String checksum=hash(sql);var old=scalar(c,"SELECT checksum FROM bureau_schema WHERE version=?",i+1);
                    if(old!=null&&!checksum.equals(old))throw new IllegalStateException("Applied bureau migration checksum changed");
                    if(old==null){for(String part:sql.split(";"))if(!part.isBlank())execute(c,part);execute(c,"INSERT INTO bureau_schema VALUES(?,?)",i+1,checksum);}
                }
                return null;
            });
        }catch(Exception e){throw new IllegalStateException("Cannot open bureau PostgreSQL database; check local configuration",e);}
    }
    /** Runs the supplied database action in a transaction, committing success and rolling back failure. */
    public <T>T tx(Action<T> action) {
        try(Connection c=pool.getConnection()){
            c.setAutoCommit(false);
            try{T result=action.run(c);c.commit();return result;}
            catch(Exception e){c.rollback();if(e instanceof RuntimeException r)throw r;throw new IllegalStateException("Bureau transaction failed",e);}
        }catch(SQLException e){throw new IllegalStateException("Bureau database unavailable",e);}
    }
    /** Prepares a SQL statement and binds its arguments in order. */
    public static PreparedStatement statement(Connection c,String sql,Object... args)throws SQLException {
        var p=c.prepareStatement(sql);for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;
    }
    /** Executes a parameterized SQL statement and returns its update count. */
    public static int execute(Connection c,String sql,Object... args)throws SQLException {
        try(var p=statement(c,sql,args)){p.execute();return p.getUpdateCount();}
    }
    /** Returns the first column of the first SQL result row, or null when no row exists. */
    public static String scalar(Connection c,String sql,Object... args)throws SQLException {
        try(var p=statement(c,sql,args);var r=p.executeQuery()){return r.next()?r.getString(1):null;}
    }
    /** Deserializes the first stored JSON result into the requested type, or returns null when absent. */
    public static <T>T one(Connection c,String sql,Class<T> type,Object... args)throws SQLException {
        String value=scalar(c,sql,args);return value==null?null:Json.read(value,type);
    }
    /** Computes a SHA-256 fingerprint for stable identity and content comparison. */
    public static String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((value instanceof String s?s:Json.write(value)).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    /** Releases the resources owned by this component. */
    public void close(){pool.close();}
}
