package com.lending.ai;

import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.lending.ai.Contracts.*;
import static com.lending.ai.Json.*;

/** Legacy questions are resolved internally or end as a bounded result, never another analyst prompt. */
class WorkflowClarificationTest {
    @Test void repeatedLegacyQuestionsStopAfterOneInternalRepairWithoutPrompting()throws Exception{
        try(var store=new FoundationTest().store();var agentStore=new FoundationTest().store()){
            var calls=new AtomicInteger();var engine=new WorkflowIntegrationTest.Engine();
            ModelClient model=(instructions,input,schema)->{
                calls.incrementAndGet();
                assertEquals("SCOPE_INTERPRETATION",input.at("/request/payload/stage").asText());
                assertEquals("PERSONAL_LOAN",input.at("/request/payload/baseline/offer/product").asText());
                assertEquals("NO_ANALYST_QUESTIONS",input.at("/request/payload/executionContext/interaction").asText());
                assertFalse(schema.at("/properties/status/enum").toString().contains("NEEDS_INPUT"));
                if(calls.get()==2)assertEquals("REVISE",input.at("/request/payload/internalReview/status").asText());
                return new ModelClient.Reply(tree(new Decision("NEEDS_INPUT","Stage is ambiguous","Should the score apply to bureau or marketing?",
                    List.of(),List.of("underwriting"),List.of(),List.of(),null)),"test-double",10,10);
            };
            try(var agent=new A2AService(Role.ORCHESTRATOR,agentStore,new AgentRunner(Role.ORCHESTRATOR,model,A2AProtocolTest.tools()));
                var server=new Server(0,Role.ORCHESTRATOR,"analyst",Map.of("ORCHESTRATOR","test-token"),agent,agentStore,null,null,null,null,null)){
                server.start();
                try(var workflow=new WorkflowService(store,engine,Map.of(Role.ORCHESTRATOR,new A2AClient("http://127.0.0.1:"+server.port(),"test-token")))){
                    var budget=new Store.Budget(4,10,500000,new BigDecimal("10"),Instant.now().plusSeconds(60).toString(),1,0,2,5,0,0);
                    String id=workflow.create(obj("requestId","autonomous","draftId","baseline","populationId","discovery","validationPopulationId","validation",
                        "intent","Explore a higher credit score; keep underwriting fixed.","lockedStages",List.of("underwriting"),"budget",budget,"seed",1),"analyst").path("id").asText();
                    workflow.start();WorkflowIntegrationTest.await(workflow,id,"BLOCKED");
                    var stopped=workflow.get(id,"analyst");
                    assertEquals(2,calls.get());assertEquals(1,stopped.data().path("messages").size());
                    assertFalse(stopped.data().has("clarification"));assertEquals("BLOCKED",stopped.data().at("/outcome/status").asText());
                    assertEquals(0,engine.admissions.get());assertEquals(0,engine.publishes.get());
                    for(var task:agentStore.list("TASK","",100))assertEquals("TASK_STATE_COMPLETED",task.data().at("/task/status/state").asText());
                }
            }
        }
    }

    @Test void contextUsesActualReservationsAndExplicitZeroFloors(){
        var budget=new Store.Budget(20,60,500000,new BigDecimal("10"),Instant.now().plusSeconds(60).toString(),12,5,2,5,0,0);
        var context=AutonomousPolicy.context(obj("budget",budget),obj("evaluations",3,"modelCalls",7,"reservedTokens",12345,"reservedCostUsd",new BigDecimal("1.25")));
        assertEquals(17,context.at("/remaining/evaluations").asInt());
        assertEquals(53,context.at("/remaining/modelCalls").asInt());
        assertEquals(487655,context.at("/remaining/tokens").asLong());
        assertEquals(0,new BigDecimal("8.75").compareTo(context.at("/remaining/reservedCostUsd").decimalValue()));
        assertEquals(0,context.at("/ranking/acceptanceWinnerEligibilityFloorPct").asInt());
        assertEquals(0,context.at("/ranking/eligibilityWinnerAcceptanceFloorPct").asInt());
        assertEquals(5,context.at("/ranking/minimumEligible").asInt());
        assertEquals("null",Contracts.decisionSchema().at("/properties/clarification/type").asText());
    }
}
