package com.lending.ai;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Optional owner-service PostgreSQL regressions run only on an ephemeral database owned by this test. */
@EnabledIfSystemProperty(named="ai.engineRegression",matches="true")
@Timeout(240)
class EnginePostgresTest {
    @TempDir Path temp;
    @Test void existingStorageSelectionAndExecutionContractsRemainCompatible()throws Exception{
        try(var pg=EmbeddedPostgres.start()){
            String url="postgresql://postgres:postgres@127.0.0.1:"+pg.getPort()+"/postgres";
            Path root=Path.of("..").toAbsolutePath().normalize();
            for(var target:List.of(List.of("marketing-service","OfferIntegrationTest,StorageIntegrationTest"),List.of("services","StorageCopyIntegrationTest,ExecutionStoreTest"))){
                Path log=temp.resolve(target.get(0)+".log");
                var builder=new ProcessBuilder("mvn","-q","-Dorg.slf4j.simpleLogger.defaultLogLevel=warn","-f",root.resolve(target.get(0)+"/pom.xml").toString(),"test","-Dtest="+target.get(1));
                builder.directory(root.toFile()).environment().put("BUREAU_TEST_DATABASE_URL",url);
                Process process=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
                try{assertTrue(process.waitFor(100,TimeUnit.SECONDS),"Regression command timed out");assertEquals(0,process.exitValue(),()->read(log));}
                finally{if(process.isAlive())process.destroyForcibly();}
            }
        }
    }
    private static String read(Path p){try{return Files.readString(p);}catch(Exception e){return "Log unavailable";}}
}
