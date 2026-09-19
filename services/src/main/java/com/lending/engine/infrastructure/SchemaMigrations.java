/** Ordered checksum migrations preserve existing data and add restart-safe H2 customer pipeline admission. */
package com.lending.engine.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.HexFormat;

/** Ordered checksum migrations preserve existing data and add restart-safe H2 customer pipeline admission. */
final class SchemaMigrations {
    /** Prevents instantiation of this utility-only type. */
    private SchemaMigrations() {}
    /** Applies missing H2 migrations in order and rejects changes to the checksums of applied migrations. */
    static void apply(Connection c) {
        try {
            for(int version=2;version<=3;version++){
            String name=version==2?"V002__marketing_qualification.sql":"V003__automatic_pipeline.sql",sql;
            try(var in=SchemaMigrations.class.getResourceAsStream("/db/migration/"+name)) {
                if(in==null)throw new IllegalStateException("Missing migration resource");
                sql=new String(in.readAllBytes(),StandardCharsets.UTF_8);
            }
            String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8)));
            try(var s=c.createStatement()){s.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INT PRIMARY KEY, name VARCHAR(100), checksum VARCHAR(64))");}
            try(var p=c.prepareStatement("SELECT checksum FROM schema_migrations WHERE version="+version);var r=p.executeQuery()) {
                if(r.next()){if(!checksum.equals(r.getString(1)))throw new IllegalStateException("Migration checksum changed; restore the applied migration");continue;}
            }
            try(var s=c.createStatement()){for(String statement:sql.split(";"))if(!statement.isBlank())s.execute(statement);}
            try(var p=c.prepareStatement("INSERT INTO schema_migrations VALUES(?,?,?)")){p.setInt(1,version);p.setString(2,name);p.setString(3,checksum);p.executeUpdate();}
            }
        }catch(Exception e){throw new IllegalStateException("Cannot apply additive marketing migration",e);}
    }
}
