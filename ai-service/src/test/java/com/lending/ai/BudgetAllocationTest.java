package com.lending.ai;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static com.lending.ai.Json.*;
import static com.lending.ai.Contracts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies owner-provided new-run allocations, HTTP validation and actionable over-allocation diagnostics without models or persistent data. */
class BudgetAllocationTest {
    @Test void everySupportedTotalProducesAnAdmissibleAllocation(){
        for(int total=4;total<=1000;total++){
            var budget=Server.defaults(total);budget.validate();
            assertEquals(total,1+budget.exploration()+budget.refinement()+budget.validation());
        }
        var demo=Server.defaults(20);assertEquals(12,demo.exploration());assertEquals(5,demo.refinement());assertEquals(2,demo.validation());
        assertEquals(0,Server.defaults(4).refinement());
        assertThrows(Fault.class,()->Server.defaults(3));assertThrows(Fault.class,()->Server.defaults(1001));
    }
    @Test void ownerApiReturnsRecommendationsAndRejectsMalformedTotals()throws Exception{
        try(var store=new FoundationTest().store();
            var workflows=new WorkflowService(store,null,Map.of());
            var peer=new A2AService(Role.ORCHESTRATOR,store,new AgentRunner(Role.ORCHESTRATOR,(i,p,s)->{throw new AssertionError("No model expected");},NetworkIsolationTest.unused()));
            var server=new Server(0,Role.ORCHESTRATOR,"analyst",Map.of("analyst","test-token"),peer,store,workflows,null,null,null,null)){
            server.start();var http=new Http("http://127.0.0.1:"+server.port(),"test-token");
            var result=http.call("GET","api/v1/ai/defaults?maxEvaluations=20",null);
            assertEquals(20,result.path("maxEvaluations").asInt());assertEquals(12,result.path("exploration").asInt());
            assertEquals(5,result.path("refinement").asInt());assertEquals(2,result.path("validation").asInt());
            assertEquals(200,http.call("GET","api/v1/ai/defaults",null).path("maxEvaluations").asInt());
            for(String invalid:List.of("3","1001","20.5","nope","-4","999999999999"))
                assertThrows(Fault.class,()->http.call("GET","api/v1/ai/defaults?maxEvaluations="+invalid,null));
        }
    }
    @Test void reducingOnlyTheTotalExplainsTheBaselineAndOldPhases(){
        var d=Server.defaults();
        var invalid=new Store.Budget(20,d.maxModelCalls(),d.maxTokens(),d.maxCostUsd(),
            Instant.now().plusSeconds(60).toString(),d.exploration(),d.refinement(),d.validation(),5,0,0);
        var error=assertThrows(Fault.class,invalid::validate);
        assertTrue(error.getMessage().contains("200 evaluations"));
        assertTrue(error.getMessage().contains("1 baseline"));
        assertTrue(error.getMessage().contains("Total simulation limit is 20"));
    }
}

