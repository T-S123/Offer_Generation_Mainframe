/**
 * Marketing/offer storage PostgreSQL ownership with ordered checksum migrations and atomic legacy
 * offer-history backfill.
 */
package com.lending.offers.infrastructure;
import com.lending.offers.Json;
import com.zaxxer.hikari.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/**
 * Marketing/offer storage PostgreSQL ownership with ordered checksum migrations and atomic legacy
 * offer-history backfill.
 */
public final class Database implements AutoCloseable {
    private final HikariDataSource pool;
    /** Defines a database action executed within the owning transaction boundary. */
    @FunctionalInterface public interface Action<T>{
        /** Runs the supplied action using the transaction connection. */
        T run(Connection c)throws Exception;}
    /** Initializes database with the supplied configuration and dependencies. */
    public Database(String url,String schema){
        if(!schema.matches("[a-z][a-z0-9_]{0,62}")||schema.equals("public"))throw new IllegalArgumentException("A dedicated marketing schema is required");
        try{var uri=URI.create(url);if(!Set.of("postgres","postgresql").contains(uri.getScheme()))throw new IllegalArgumentException();String[] auth=uri.getRawUserInfo().split(":",2);var config=new HikariConfig();config.setJdbcUrl("jdbc:postgresql://"+uri.getHost()+":"+(uri.getPort()<0?5432:uri.getPort())+uri.getRawPath());config.setUsername(URLDecoder.decode(auth[0],StandardCharsets.UTF_8));config.setPassword(URLDecoder.decode(auth[1],StandardCharsets.UTF_8));
            try(var c=DriverManager.getConnection(config.getJdbcUrl(),config.getUsername(),config.getPassword())){execute(c,"CREATE SCHEMA IF NOT EXISTS "+schema);}config.setConnectionInitSql("SET search_path TO "+schema);config.setMaximumPoolSize(8);config.setConnectionTimeout(5000);config.addDataSourceProperty("connectTimeout","5");config.addDataSourceProperty("socketTimeout","30");config.setPoolName("marketing-offers");pool=new HikariDataSource(config);
            tx(c->{execute(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))",schema+".migration");String[] migrations={"V001__offers.sql","V002__offer_storage.sql"};for(int i=0;i<migrations.length;i++){String sql;try(var in=getClass().getResourceAsStream("/db/"+migrations[i])){sql=new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);}String checksum=Json.hash(sql);String exists=scalar(c,"SELECT to_regclass(?)",schema+".schema_version");String old=exists==null?null:scalar(c,"SELECT checksum FROM schema_version WHERE version=?",i+1);if(old!=null){if(!checksum.equals(old))throw new IllegalStateException("Applied marketing migration changed");}else{for(String part:sql.split(";"))if(!part.isBlank())execute(c,part);if(i==1)StorageJournal.backfill(c);execute(c,"INSERT INTO schema_version VALUES(?,?)",i+1,checksum);}}return null;});
        }catch(Exception e){throw new IllegalStateException("Marketing database unavailable; check local configuration",e);}
    }
    /** Runs the supplied database action in a transaction, committing success and rolling back failure. */
    public <T>T tx(Action<T> a){try(var c=pool.getConnection()){c.setAutoCommit(false);try{T value=a.run(c);c.commit();return value;}catch(Exception e){c.rollback();if(e instanceof RuntimeException r)throw r;throw new IllegalStateException("Marketing transaction failed",e);}}catch(SQLException e){throw new IllegalStateException("Marketing database unavailable",e);}}
    /** Prepares a SQL statement and binds its arguments in order. */
    public static PreparedStatement statement(Connection c,String sql,Object... args)throws SQLException{var p=c.prepareStatement(sql);for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;}
    /** Executes a parameterized SQL statement and returns its update count. */
    public static int execute(Connection c,String sql,Object... args)throws SQLException{try(var p=statement(c,sql,args)){p.execute();return p.getUpdateCount();}}
    /** Returns the first column of the first SQL result row, or null when no row exists. */
    public static String scalar(Connection c,String sql,Object... args)throws SQLException{try(var p=statement(c,sql,args);var r=p.executeQuery()){return r.next()?r.getString(1):null;}}
    /** Deserializes the first stored JSON result into the requested type, or returns null when absent. */
    public static <T>T one(Connection c,String sql,Class<T> t,Object... args)throws SQLException{String s=scalar(c,sql,args);return s==null?null:Json.read(s,t);}
    /** Releases the resources owned by this component. */
    public void close(){pool.close();}
}
