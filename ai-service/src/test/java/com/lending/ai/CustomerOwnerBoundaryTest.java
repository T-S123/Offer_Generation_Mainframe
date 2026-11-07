package com.lending.ai;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Customer qualification and issuance regressions use real COBOL and isolated owner PostgreSQL schemas, without provider calls. */
@Timeout(240)
class CustomerOwnerBoundaryTest {
    @TempDir Path temp;
    @Test void ownerQualificationStorageAndSelectionStayAuthoritative()throws Exception{
        try(var pg=EmbeddedPostgres.start()){
            String url="postgresql://postgres:postgres@127.0.0.1:"+pg.getPort()+"/postgres";Path root=Path.of("..").toAbsolutePath().normalize();
            for(var target:List.of(List.of("services","OfferCreationIntegrationTest#customerVariantsQualifyWithinPolicyAndRejectStaleOrRelaxedRules+actualCobolEnvelopeAndFreshBureauDecisionQualifyExactVariant,WebServerTest"),List.of("marketing-service","OfferIntegrationTest,PersonalizationTest"))){
                Path log=temp.resolve(target.get(0)+".log");var builder=new ProcessBuilder("mvn","-q","-f",root.resolve(target.get(0)+"/pom.xml").toString(),"test","-Dtest="+target.get(1));builder.directory(root.toFile()).environment().put("BUREAU_TEST_DATABASE_URL",url);var process=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
                try{assertTrue(process.waitFor(110,TimeUnit.SECONDS),"Owner regression timed out");assertEquals(0,process.exitValue(),()->{try{return Files.readString(log);}catch(Exception e){return "Log unavailable";}});}finally{if(process.isAlive())process.destroyForcibly();}
            }
        }
    }
}
