package com.lending.ai;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.math.*;
import java.util.*;
import static com.lending.ai.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/** Usage settlement retains uncertain spend and local credential files grant only the necessary peer identities. */
class UsageAndStartupTest {
    @TempDir Path dir;
    @Test void settlementIsIdempotentAndCannotExceedReservation(){
        try(var s=new FoundationTest().store()){
            var budget=new Store.Budget(4,6,1000,BigDecimal.ONE,Instant.now().plusSeconds(60).toString(),1,0,2,1,0,0);s.budget("w",budget);
            s.reserve("w","model:one",0,1,600,new BigDecimal(".6"));s.reserve("w","model:uncertain",0,1,300,new BigDecimal(".3"));
            s.settle("w","model:one",100);s.settle("w","model:one",100);
            assertEquals(400,s.budget("w").path("reservedTokens").asLong());assertEquals(.4,s.budget("w").path("reservedCostUsd").asDouble(),.00001);
            assertEquals("USAGE_CONFLICT",assertThrows(Fault.class,()->s.settle("w","model:one",120)).code);
            assertEquals("RESERVATION_UNDERESTIMATED",assertThrows(Fault.class,()->s.settle("w","model:uncertain",301)).code);
            var extended=new Store.Budget(4,10,2000,BigDecimal.TEN,Instant.now().plusSeconds(120).toString(),1,0,2,1,0,0);s.extend("w",extended);s.extend("w",extended);
            assertEquals(400,s.budget("w").path("reservedTokens").asLong());
        }
    }
    @Test void initializationPreservesKeysAndDoesNotGiveResearchThePublicationCredential()throws Exception{
        Main.initialize(dir);var master=read(Files.readString(dir.resolve("credentials.json")));
        var research=read(Files.readString(dir.resolve("research-credentials.json")));
        assertNotEquals(master.path("analyst"),research.path("analyst"));assertEquals(master.path("policyAnalyst"),research.path("analyst"));
        var analyzer=read(Files.readString(dir.resolve("analyzer-credentials.json")));assertFalse(analyzer.has("analyst"));assertFalse(analyzer.has("outcomes"));
        Main.initialize(dir);assertEquals(master,read(Files.readString(dir.resolve("credentials.json"))));
    }
}
