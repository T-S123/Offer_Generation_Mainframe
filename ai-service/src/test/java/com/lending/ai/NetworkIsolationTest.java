package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.lending.ai.Contracts.*;
import static com.lending.ai.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/** HTTP boundaries, legacy ownership and answered-task adoption keep the two networks isolated without losing evidence. */
@Timeout(40)
class NetworkIsolationTest {
    static Store.Budget budget(){return new Store.Budget(10,30,1000000,BigDecimal.valueOf(100),Instant.now().plusSeconds(120).toString(),4,3,2,5,0,0);}
    static void workflow(Store s,String id,boolean feedback,String state){var b=budget();s.budget(id,b);s.create("WORKFLOW",id,obj("id",id,"analyst","analyst","kind",feedback?"FEEDBACK":"SIMULATION","state",state,"revision",1,"budget",b,"intent",id,"steps",obj(),"answers",List.of(),"evaluations",List.of()));}
    static AgentRunner.Tools unused(){return new AgentRunner.Tools(){public JsonNode descriptors(Role r){return tree(List.of());}public JsonNode call(Role r,Request q,ToolCall t){throw new AssertionError();}public void reserve(Request r,String key,long tokens){}};}
    @Test void scopedRoutesFilterLegacyRowsAndCredentialsCannotChargeTheOtherNetwork()throws Exception{
        try(var store=new FoundationTest().store();var simulation=new WorkflowService(store,null,Map.of());var feedback=new InvestigationWorkflowService(store,Map.of());
            var a2a=new A2AService(Role.ORCHESTRATOR,store,new AgentRunner(Role.ORCHESTRATOR,(i,p,s)->{throw new AssertionError("No model calls expected");},unused()));
            var server=new Server(0,Role.ORCHESTRATOR,"analyst",Map.of("analyst","analyst-token","ORCHESTRATOR","simulation-token","ANALYZER","sim-agent","FEEDBACK_ANALYZER","feedback-agent"),a2a,store,simulation,new PublicationService(store,simulation,null),null,null,null,feedback)){
            workflow(store,"s",false,"PLANNING");workflow(store,"f",true,"FEEDBACK_PENDING");
            for(int i=0;i<105;i++)store.create("WORKFLOW",String.format("a%03d",i),obj("kind","FEEDBACK","analyst","other-user"));
            server.start();var client=new Http("http://127.0.0.1:"+server.port(),"analyst-token");
            assertEquals("s",client.call("GET","api/v1/ai/simulation/workflows?limit=1",null).path(0).path("id").asText());
            assertEquals("f",client.call("GET","api/v1/ai/feedback/workflows?limit=1",null).path(0).path("id").asText());
            assertEquals("FEEDBACK",client.call("GET","api/v1/ai/workflows/f",null).at("/workflow/network").asText());
            assertEquals(404,assertThrows(Fault.class,()->client.call("GET","api/v1/ai/simulation/workflows/f",null)).status);
            assertThrows(Fault.class,()->client.call("POST","api/v1/ai/simulation/workflows/f/cancel",obj()));
            assertEquals("FEEDBACK_PENDING",feedback.get("f","analyst").data().path("state").asText());
            assertThrows(Fault.class,()->client.call("POST","api/v1/ai/feedback/workflows/f/publication-previews",obj("candidateId","x","note","Reviewed evidence")));
            var request=new Request("1.0.0","feedback-request","f","analysis",1,Instant.now().plusSeconds(60).toString(),obj("network","FEEDBACK"));
            store.create("STEP","f:analysis",obj("role","ANALYZER","request",request));
            var command=obj("request",request,"callId","feedback-request:0","maxTokens",100);
            var simClient=new Http("http://127.0.0.1:"+server.port(),"sim-agent");
            assertThrows(Fault.class,()->simClient.call("POST","internal/budget",command));
            var feedbackClient=new Http("http://127.0.0.1:"+server.port(),"feedback-agent");
            assertTrue(feedbackClient.call("POST","internal/budget",command).path("reserved").asBoolean());
            assertEquals(0,store.budget("s").path("modelCalls").asInt());assertEquals(1,store.budget("f").path("modelCalls").asInt());
            assertThrows(Fault.class,()->feedbackClient.call("POST","api/v1/ai/workflows",obj()));
        }
    }
    @Test void agentsRejectOtherNetworkBeforeCallingModelAndCardsDescribeTheirOwnPurpose()throws Exception{
        try(var s=new FoundationTest().store();var service=new A2AService(Role.ANALYZER,s,new AgentRunner(Network.FEEDBACK,Role.ANALYZER,(i,p,sc)->{throw new AssertionError();},unused()))){
            assertTrue(service.card("http://127.0.0.1/a2a").path("name").asText().startsWith("FEEDBACK"));
            var runner=new AgentRunner(Role.ANALYZER,(i,p,sc)->{throw new AssertionError();},unused());
            assertEquals("NETWORK_SCOPE",assertThrows(Fault.class,()->runner.run(new Request("1.0.0","request","workflow","step",1,Instant.now().plusSeconds(60).toString(),obj("network","FEEDBACK")),()->false)).code);
            assertFalse(AgentRunner.instructions(Role.ANALYZER).contains("For feedback"));
            assertTrue(AgentRunner.instructions(Network.FEEDBACK,Role.ANALYZER).contains("Never design candidates"));
            assertFalse(AgentRunner.instructions(Network.FEEDBACK,Role.ANALYZER).contains("return FOLLOW_UP with"));
            assertFalse(AgentRunner.instructions(Network.FEEDBACK,Role.ANALYZER).contains("authorize automatic simulation"));
            assertTrue(AgentRunner.instructions(Role.ANALYZER).contains("return FOLLOW_UP with"));
        }
    }
    @Test void legacyAnsweredInvestigationMovesToFeedbackAgentsAndRetainsSpentBudgetAndObservations()throws Exception{
        var runtimeRef=new AtomicReference<InvestigationWorkflowService>();var observed=new AtomicReference<JsonNode>();
        try(var store=new FoundationTest().store();var agentStore=new FoundationTest().store()){
            workflow(store,"legacy",true,"FEEDBACK_PENDING");var question=WorkflowClarificationTest.question("interpretation","INTERPRETATION");
            var request=new Request("1.0.0","old-request","legacy","r1-analysis-a0",1,Instant.now().plusSeconds(60).toString(),obj("analysisMode","FEEDBACK"));
            var result=new Result("1.0.0","ANALYZERResult","legacy","old-request","r1-analysis-a0",1,question,List.of(obj("evidence","retained")),"test","old",Instant.now().toString());
            String questionId=hash(List.of(result.requestId(),question.question()));
            var old=store.get("WORKFLOW","legacy");var data=(ObjectNode)old.data().deepCopy();data.set("answers",tree(List.of(obj("questionId",questionId,"stepKey","legacy:r1-analysis-a0","question",question.question(),"text","Use recorded data.\nKeep the original scope."))));store.update("WORKFLOW","legacy",old.version(),data);
            store.create("STEP","legacy:r1-analysis-a0",obj("role","ANALYZER","request",request,"taskId","old-simulation-task","result",result));
            store.reserve("legacy","model:old-request:0",0,1,100,new BigDecimal(".01"));
            AgentRunner.Tools tools=new AgentRunner.Tools(){public JsonNode descriptors(Role r){return tree(List.of());}public JsonNode call(Role r,Request q,ToolCall t){throw new AssertionError();}public void reserve(Request r,String key,long tokens){runtimeRef.get().reserve(Role.ANALYZER,r,key,tokens);}};
            try(var agent=new A2AService(Role.ANALYZER,agentStore,new AgentRunner(Network.FEEDBACK,Role.ANALYZER,(instructions,input,schema)->{
                observed.set(input.path("request").path("payload"));return new ModelClient.Reply(tree(new Decision("READY","Recorded evidence reviewed",null,List.of(),List.of(),List.of(),List.of(),null)),"fixture",10,10);},tools));
                var server=new Server(0,Role.ANALYZER,"analyst",Map.of("ORCHESTRATOR","feedback-token"),agent,agentStore,null,null,null,null,null)){
                server.start();try(var runtime=new InvestigationWorkflowService(store,Map.of(Role.ANALYZER,new A2AClient("http://127.0.0.1:"+server.port(),"feedback-token")))){
                    runtimeRef.set(runtime);assertEquals("READY",runtime.agent("legacy",Role.ANALYZER,"analysis",obj()).decision().status());
                    assertEquals("FEEDBACK",observed.get().path("network").asText());assertTrue(observed.get().at("/userAnswer/text").asText().contains("\n"));
                    assertEquals("retained",observed.get().at("/previousObservations/0/evidence").asText());
                    var step=store.get("STEP","legacy:r1-analysis-a0").data();assertEquals("old-simulation-task",step.path("legacyTaskId").asText());assertNotEquals("old-simulation-task",step.path("taskId").asText());
                    assertEquals(2,store.budget("legacy").path("modelCalls").asInt());
                    runtime.agent("legacy",Role.ANALYZER,"analysis",obj());assertEquals(2,store.budget("legacy").path("modelCalls").asInt());
                }
            }
        }
    }
}
