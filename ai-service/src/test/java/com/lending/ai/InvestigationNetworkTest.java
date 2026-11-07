package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;
import static com.lending.ai.Contracts.*;
import static com.lending.ai.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/** Five HTTP A2A specialists use authenticated evidence tools; clarification resumes one task and dashboard reads use no model. */
@Timeout(50)
class InvestigationNetworkTest {
    @TempDir Path root;
    static Decision ready(){return new Decision("READY","Recorded figures and exact customer evidence reviewed with limitations",null,List.of(),List.of(),List.of(),List.of(),null);}
    @Test void compoundQuestionClarifiesThenRunsFiveAgentsAndReadOnlyTools()throws Exception{
        var resources=new ArrayList<AutoCloseable>();var clients=new EnumMap<Role,A2AClient>(Role.class);var toolboxes=new EnumMap<Role,AgentToolbox>(Role.class);var calls=new EnumMap<Role,AtomicInteger>(Role.class);var facade=new AtomicReference<Server>();var seen=new CopyOnWriteArrayList<JsonNode>();
        try(var store=new FoundationTest().store()){
            PerformanceMeasurementsTest.fixture(store);String original=hash(store.get("PUBLICATION","published").data());
            for(Role role:Network.FEEDBACK.roles()){
                calls.put(role,new AtomicInteger());var local=new FoundationTest().store();resources.add(local);
                var toolbox=new AgentToolbox(null,new McpTools(root.resolve("none.json"),Network.FEEDBACK),(q,c,t)->new Http("http://127.0.0.1:"+facade.get().port(),"token-"+role).call("POST","internal/budget",obj("request",q,"callId",c,"maxTokens",t))).settlement((q,c,t)->new Http("http://127.0.0.1:"+facade.get().port(),"token-"+role).call("POST","internal/usage",obj("request",q,"callId",c,"actualTokens",t)));toolboxes.put(role,toolbox);
                ModelClient model=(instructions,input,schema)->{
                    calls.get(role).incrementAndGet();var p=input.at("/request/payload");seen.add(input);assertEquals("FEEDBACK",p.path("network").asText());
                    if(role==Role.ORCHESTRATOR){
                        if(!p.has("userAnswer"))return new ModelClient.Reply(tree(new Decision("NEEDS_INPUT","Select the offer",null,List.of(),List.of(),List.of(),List.of(),null,new Question("offer-choice","SCOPE","Which published offer should this question use?","The offer determines both the population and the customer decision.",List.of("Personalized auto loan","A different publication"),List.of()),null)),"fixture",20,20);
                        assertTrue(p.at("/userAnswer/text").asText().contains("\n"));
                        return new ModelClient.Reply(tree(planned(ready(),new InvestigationPlan("published",PerformanceMeasurementsTest.subject(0),"2026-09-01","2026-09-02",null,null,null,"OFFER_FAMILY",1,null,"PERSONALIZED"))),"fixture",20,20);
                    }
                    assertFalse(input.path("observations").isEmpty(),role+" must read owner evidence");
                    if(role==Role.ANALYZER){assertEquals(40,p.at("/measurement/result/comparison/observed/acceptanceAmongEligiblePct").asDouble());assertEquals("PERSONALIZED",p.at("/customerTrace/result/variant").asText());}
                    return new ModelClient.Reply(tree(ready()),"fixture",20,20);
                };
                var agent=new A2AService(role,local,new AgentRunner(Network.FEEDBACK,role,model,toolbox));resources.add(agent);var server=new Server(0,role,"analyst",Map.of("ORCHESTRATOR","delegate"),agent,local,null,null,null,null,null);resources.add(server);server.start();clients.put(role,new A2AClient("http://127.0.0.1:"+server.port(),"delegate"));
            }
            var simulations=new WorkflowService(store,null,Map.of());resources.add(simulations);var investigations=new InvestigationWorkflowService(store,clients);resources.add(investigations);
            var facadeAgent=new A2AService(Role.ORCHESTRATOR,store,new AgentRunner(Role.ORCHESTRATOR,(i,p,s)->{throw new AssertionError("No simulation calls");},NetworkIsolationTest.unused()));resources.add(facadeAgent);
            var credentials=new HashMap<String,String>();credentials.put("analyst","analyst-token");credentials.put("ANALYZER","simulation-token");for(var role:Network.FEEDBACK.roles())credentials.put("FEEDBACK_"+role,"token-"+role);
            var server=new Server(0,Role.ORCHESTRATOR,"analyst",credentials,facadeAgent,store,simulations,new PublicationService(store,simulations,null),new OutcomeService(store,null,14),null,null,investigations);resources.add(server);facade.set(server);server.start();
            for(var role:Network.FEEDBACK.roles())toolboxes.get(role).investigation(new Http("http://127.0.0.1:"+server.port(),"token-"+role));var http=new Http("http://127.0.0.1:"+server.port(),"analyst-token");
            var body=obj("requestId","compound","text","What was average acceptance September 1-2 and was this customer eligible for the personalized variant?","customerReference",PerformanceMeasurementsTest.subject(0),"sourceMode","OBSERVED_REAL","scope",obj("timezone","America/Los_Angeles","dateBasis","FIRST_ASSESSED","evidenceCutoff",PerformanceMeasurementsTest.CUTOFF.toString()),"budget",NetworkIsolationTest.budget());
            String id=http.call("POST","api/v1/ai/feedback/investigations",body).path("id").asText();assertEquals(id,http.call("POST","api/v1/ai/feedback/investigations",body).path("id").asText());investigations.start();WorkflowIntegrationTest.await(investigations,id,"NEEDS_INPUT");var waiting=investigations.get(id,"analyst");String task=waiting.data().at("/pendingQuestion/taskId").asText();assertFalse(waiting.data().has("measurement"));
            http.call("POST","api/v1/ai/feedback/workflows/"+id+"/messages",obj("expectedVersion",waiting.version(),"questionId",waiting.data().at("/pendingQuestion/id"),"text","Use the personalized auto loan publication.\nKeep the family acceptance cohort."));
            WorkflowIntegrationTest.await(investigations,id,"COMPLETED");var finished=investigations.get(id,"analyst").data();assertEquals(2,calls.get(Role.ORCHESTRATOR).get());for(var role:Network.FEEDBACK.roles())assertTrue(calls.get(role).get()>0);assertEquals(task,finished.at("/steps/r1-investigation-scope-a0/taskId").asText());assertEquals(40,finished.at("/answer/measurement/result/comparison/observed/acceptanceAmongEligiblePct").asDouble());assertEquals("ELIGIBLE",finished.at("/answer/customerTrace/result/eligibility").asText());assertEquals(3,finished.at("/answer/customerTrace/result/profileVersion").asInt());assertEquals(0,store.budget(id).path("evaluations").asInt());assertEquals(original,hash(store.get("PUBLICATION","published").data()));assertEquals(1,store.list("PUBLICATION",null,100).size());
            assertTrue(finished.at("/answer/measurement/result/comparison/gapPercentagePoints").isNull());String ref=finished.at("/answer/customerTrace/snapshotRef").asText();assertEquals("ELIGIBLE",http.call("GET","api/v1/ai/feedback/workflows/"+id+"/artifacts/"+ref,null).path("eligibility").asText());
            var delegated=seen.stream().filter(n->n.at("/request/stepId").asText().contains("investigation-metrics")).findFirst().orElseThrow().path("request");var attempt=obj("request",delegated,"name","statistics.measure","arguments",obj());assertThrows(Fault.class,()->new Http("http://127.0.0.1:"+server.port(),"simulation-token").call("POST","internal/investigation-tools",attempt));assertThrows(Fault.class,()->new Http("http://127.0.0.1:"+server.port(),"token-RESEARCH").call("POST","internal/investigation-tools",attempt));
            String sourceRef=finished.at("/answer/customerTrace/result/evidenceIds/0").asText();assertEquals("ASSESSMENT",http.call("GET","api/v1/ai/performance/publications/published/records/"+sourceRef,null).at("/event/kind").asText());assertThrows(Fault.class,()->http.call("GET","api/v1/ai/performance/publications/missing/records/"+sourceRef,null));
            int spent=calls.values().stream().mapToInt(AtomicInteger::get).sum();server.agentsEnabled(false);assertEquals(1,http.call("GET","api/v1/ai/performance/publications",null).size());assertTrue(http.call("GET","api/v1/ai/performance/publications/published?sourceMode=OBSERVED_REAL",null).has("comparison"));assertEquals(40,http.call("POST","api/v1/ai/performance/publications/published/measurements",tree(PerformanceMeasurementsTest.scope(obj()))).at("/result/comparison/observed/acceptanceAmongEligiblePct").asDouble());assertEquals(spent,calls.values().stream().mapToInt(AtomicInteger::get).sum());assertEquals("AGENTS_DISABLED",assertThrows(Fault.class,()->http.call("POST","api/v1/ai/feedback/investigations",body)).code);
        }finally{Collections.reverse(resources);for(var r:resources)r.close();}
    }
    @Test void revisedStoppedQuestionsDiscardDerivedScopeAndToolsRejectForgedRequests(){try(var s=new FoundationTest().store();var sim=new WorkflowService(s,null,Map.of());var runtime=new InvestigationWorkflowService(s,Map.of())){
        NetworkIsolationTest.workflow(s,"w",true,"FEEDBACK_PENDING");var old=s.get("WORKFLOW","w");var data=(com.fasterxml.jackson.databind.node.ObjectNode)old.data().deepCopy();data.put("measurementVersion",MeasurementScope.VERSION).put("selectedPublicationId","published").put("requestedCustomerReference","DEMO-0001");data.set("resolvedScope",obj("variant","ORIGINAL"));data.set("measurement",obj("result",obj("comparison",obj("observed",obj("E",2)))));data.set("messages",tree(List.of()));s.update("WORKFLOW","w",old.version(),data);
        var service=new FeedbackService(s,runtime,sim,null,new OutcomeService(s,null,14));var request=new Request("1.0.0","registered","w","step",1,java.time.Instant.now().plusSeconds(60).toString(),obj("network","FEEDBACK"));s.create("STEP","w:step",obj("role","ANALYZER","request",request));assertEquals(2,service.tool(Role.ANALYZER,request,"statistics.measure",obj()).at("/result/comparison/observed/E").asInt());assertThrows(Fault.class,()->service.tool(Role.RESEARCH,request,"statistics.measure",obj()));assertThrows(Fault.class,()->service.tool(Role.ANALYZER,new Request("1.0.0","forged","w","step",1,request.deadline(),request.payload()),"statistics.measure",obj()));assertThrows(Fault.class,()->service.tool(Role.ANALYZER,request,"statistics.measure",obj("customerId","other")));
        runtime.mutate("w",n->n.put("state","BLOCKED"));var stopped=runtime.get("w","analyst");var revised=runtime.message("w","analyst",obj("expectedVersion",stopped.version(),"text","Use the family-level cohort"));assertFalse(revised.path("workflow").has("resolvedScope"));assertFalse(revised.path("workflow").has("measurement"));assertEquals("DEMO-0001",revised.at("/workflow/customerReference").asText());
    }}

}
