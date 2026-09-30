package com.lending.ai;

import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.lending.ai.Contracts.*;
import static com.lending.ai.Json.*;

/** Verifies complete clarification history and that replies resume the saved scope without discarding earlier intent. */
class WorkflowClarificationTest {
    @Test void clarificationAndReplyAreRetainedWhileStaleQuestionIsCleared()throws Exception{
        try(var store=new FoundationTest().store();var agentStore=new FoundationTest().store()){
            ModelClient model=(instructions,input,schema)->{
                assertEquals("SCOPE_INTERPRETATION",input.at("/request/payload/stage").asText());
                assertEquals("PERSONAL_LOAN",input.at("/request/payload/baseline/offer/product").asText());
                return new ModelClient.Reply(tree(new Decision("NEEDS_INPUT","Stage is ambiguous","Should the score apply to bureau or marketing? Keep the other stages unchanged.",List.of(),List.of("underwriting"),List.of(),List.of(),null)),"test-double",10,10);
            };
            try(var agent=new A2AService(Role.ORCHESTRATOR,agentStore,new AgentRunner(Role.ORCHESTRATOR,model,A2AProtocolTest.tools()));
                var server=new Server(0,Role.ORCHESTRATOR,"analyst",Map.of("ORCHESTRATOR","test-token"),agent,agentStore,null,null,null,null,null)){
                server.start();
                try(var workflow=new WorkflowService(store,new WorkflowIntegrationTest.Engine(),Map.of(Role.ORCHESTRATOR,new A2AClient("http://127.0.0.1:"+server.port(),"test-token")))){
                    var budget=new Store.Budget(4,10,500000,new BigDecimal("10"),Instant.now().plusSeconds(60).toString(),1,0,2,5,0,0);
                    String id=workflow.create(obj("requestId","clarify","draftId","baseline","populationId","discovery","validationPopulationId","validation","intent","Explore a higher credit score; keep underwriting fixed.","lockedStages",List.of("underwriting"),"budget",budget,"seed",1),"analyst").path("id").asText();
                    workflow.start();WorkflowIntegrationTest.await(workflow,id,"NEEDS_INPUT");workflow.close();
                    var stopped=workflow.get(id,"analyst");
                    assertEquals("assistant",stopped.data().at("/messages/1/role").asText());
                    assertEquals(stopped.data().path("clarification").asText(),stopped.data().at("/messages/1/text").asText());
                    var reply=workflow.message(id,"analyst",obj("expectedVersion",stopped.version(),"text","Bureau only. Use the approved policy."));
                    assertEquals("PLANNING",reply.at("/workflow/state").asText());
                    assertFalse(reply.path("workflow").has("clarification"));
                    assertEquals(3,reply.at("/workflow/messages").size());
                    assertTrue(reply.at("/workflow/intent").asText().startsWith("Explore a higher credit score"));
                    assertEquals("underwriting",reply.at("/workflow/lockedStages/0").asText());
                }
            }
        }
    }
}
