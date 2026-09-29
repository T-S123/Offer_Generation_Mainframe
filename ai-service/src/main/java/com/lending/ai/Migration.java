package com.lending.ai;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import static com.lending.ai.Json.*;

/** Applies the AI schema migration once and rejects changed checksums on subsequent starts. */
final class Migration {
    static void apply(Connection db){
        try(var resource=Migration.class.getResourceAsStream("/db/V001__ai_workflows.sql")){
            if(resource==null)throw new IllegalStateException("AI migration resource is missing");
            byte[] bytes=resource.readAllBytes();String checksum=digest(bytes);
            try(var statement=db.createStatement()){statement.execute("CREATE TABLE IF NOT EXISTS ai_migrations(version INT PRIMARY KEY,checksum VARCHAR(64) NOT NULL)");}
            try(var statement=db.prepareStatement("SELECT checksum FROM ai_migrations WHERE version=1");var result=statement.executeQuery()){
                if(result.next()){if(!checksum.equals(result.getString(1)))throw new IllegalStateException("Applied AI migration checksum changed");return;}
            }
            db.setAutoCommit(false);
            try{
                String sql=new String(bytes,StandardCharsets.UTF_8).replaceAll("(?m)^--.*$","");
                for(String command:sql.split(";"))if(!command.isBlank())try(var statement=db.createStatement()){statement.execute(command);}
                try(var statement=db.prepareStatement("INSERT INTO ai_migrations VALUES(1,?)")){statement.setString(1,checksum);statement.executeUpdate();}
                db.commit();
            }catch(Exception e){db.rollback();throw e;}finally{db.setAutoCommit(true);}
        }catch(Exception e){throw new IllegalStateException("AI schema migration failed",e);}
    }
}
