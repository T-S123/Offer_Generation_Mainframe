package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.lending.ai.Json.*;
import static com.lending.ai.Contracts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in cross-module smoke: real customer/COBOL/simulation/publication APIs, controlled AI replies and disposable databases. */
@Timeout(180)
class OwnerApiNetworkSmoke {
    @Test void customerEditSimulationPublicationAndSeparateInvestigation()throws Exception {
        Path root=Path.of("..").toAbsolutePath().normalize();
        var resources=new ArrayList<AutoCloseable>();
        try(var loader=new URLClassLoader(new URL[]{root.resolve("services/target/test-classes").toUri().toURL(),root.resolve("services/target/customer-engine-1.0.0.jar").toUri().toURL()},ClassLoader.getPlatformClassLoader())){
            Class.forName("org.h2.Driver",true,loader);
            var fixtureType=loader.loadClass("com.lending.engine.web.GuiPreviewHarness");
            var fixture=(AutoCloseable)fixtureType.getMethod("start",Path.class,int.class).invoke(null,root,0);resources.add(fixture);
            int ownerPort=(int)fixture.getClass().getMethod("ownerPort").invoke(fixture),webPort=(int)fixture.getClass().getMethod("port").invoke(fixture);
            var browser=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
            String web="http://127.0.0.1:"+webPort+"/web-api/";
            String csrf=browser(browser,web,"GET","session",null,null).path("csrfToken").asText();
            var input=obj("displayName","A2A network smoke customer","monthlyIncomeUsd",7000,"existingProducts",List.of(),"preferredChannels",List.of());
            var created=browser(browser,web,"POST","customers",input,csrf);String customerId=created.path("id").asText();assertFalse(customerId.isBlank());
            assertEquals(7000,browser(browser,web,"GET","customer",null,csrf).at("/profiles/0/data/monthlyIncomeUsd").asInt());
            input.put("monthlyIncomeUsd",8500);
            browser(browser,web,"PUT","customer",obj("expectedVersion",1,"customer",input),csrf);
            browser(browser,web,"POST","session/logout",obj(),csrf);
            browser(browser,web,"POST","session/customer",obj("customerId",customerId),csrf);
            var edited=browser(browser,web,"GET","customer",null,csrf);assertEquals(2,edited.path("profiles").size());assertEquals(8500,edited.at("/profiles/1/data/monthlyIncomeUsd").asInt());
            browser(browser,web,"POST","session/business",obj(),csrf);
            var draft=browser(browser,web,"POST","business/drafts",obj("name","Network isolation smoke","baselineOfferId","DEMO-PL-1","campaignId","DEMO-PL"),csrf);
            var discovery=browser(browser,web,"POST","business/populations",obj("count",120,"seed",2026102701),csrf);
            var validation=browser(browser,web,"POST","business/populations",obj("count",120,"seed",2026102702),csrf);
            var engine=new EngineGateway("http://127.0.0.1:"+ownerPort+"/api/v1/","gui-test-owner");
            var central=new FoundationTest().store();resources.add(central);
            var runtimes=new ConcurrentHashMap<Network,WorkflowRuntime>();var counts=new ConcurrentHashMap<Network,AtomicInteger>();
            var peers=new EnumMap<Network,EnumMap<Role,A2AClient>>(Network.class);A2AService facadeAgent=null;
            for(var network:Network.values()){
                peers.put(network,new EnumMap<>(Role.class));counts.put(network,new AtomicInteger());
                for(var role:network.roles()){
                    var local=new FoundationTest().store();resources.add(local);
                    AgentRunner.Tools tools=new AgentRunner.Tools(){
                        public JsonNode descriptors(Role r){return tree(List.of(obj("name","policy.search","inputSchema",Contracts.structure())));}
                        public JsonNode call(Role r,Request request,ToolCall call){assertEquals("policy.search",call.name());return obj("evidence",List.of(obj("evidenceId","smoke-policy:0","quote","Controlled fixture policy allows bureau minimum score from 650 through 700")));}
                        public void reserve(Request request,String key,long tokens){runtimes.get(network).reserve(role,request,key,tokens);}
                    };
                    ModelClient model=(instructions,inputData,schema)->{
                        assertEquals(network.name(),inputData.at("/request/payload/network").asText());counts.get(network).incrementAndGet();
                        if(network==Network.FEEDBACK&&role==Role.ORCHESTRATOR)return new ModelClient.Reply(tree(Contracts.planned(new Decision("READY","Resolved publication",null,List.of(),List.of(),List.of(),List.of(),null),new InvestigationPlan(inputData.path("request").path("payload").path("publicationId").asText(),inputData.at("/request/payload/customerReference").asText(null),null,null,null,null,null,null,null,null,"PERSONALIZED"))),"test-double",20,20);
                    if(role==Role.RESEARCH&&inputData.path("observations").isEmpty())return new ModelClient.Reply(tree(new Decision("TOOL","Read fixture policy",null,List.of(),List.of(),List.of(),List.of(),new ToolCall("policy.search","{}"))),"test-double",20,20);
                        var ranges=network==Network.SIMULATION&&role==Role.RESEARCH?List.of(new ParameterRegistry.Range("/rules/bureau/minimumScore",BigDecimal.valueOf(650),BigDecimal.valueOf(700),BigDecimal.ONE,null,List.of("smoke-policy:0"),false)):List.<ParameterRegistry.Range>of();
                        return new ModelClient.Reply(tree(new Decision("READY","Controlled review of supplied evidence",null,List.of("/rules/bureau/minimumScore"),List.of("offer","underwriting","marketing"),ranges,List.of(),null)),"test-double",100,50);
                    };
                    var agent=new A2AService(role,local,new AgentRunner(network,role,model,tools));resources.add(agent);
                    var server=new Server(0,role,"analyst",Map.of("ORCHESTRATOR","agent-test-token"),agent,local,null,null,null,null,null);server.start();resources.add(server);
                    peers.get(network).put(role,new A2AClient("http://127.0.0.1:"+server.port(),"agent-test-token"));
                    if(network==Network.SIMULATION&&role==Role.ORCHESTRATOR)facadeAgent=agent;
                }
            }
            var simulations=new WorkflowService(central,engine,peers.get(Network.SIMULATION));resources.add(simulations);runtimes.put(Network.SIMULATION,simulations);
            var investigations=new InvestigationWorkflowService(central,peers.get(Network.FEEDBACK));resources.add(investigations);runtimes.put(Network.FEEDBACK,investigations);
            var publications=new PublicationService(central,simulations,engine);
            var facade=new Server(0,Role.ORCHESTRATOR,"analyst",Map.of("analyst","analyst-test-token"),facadeAgent,central,simulations,publications,new OutcomeService(central,null,14),null,null,investigations);facade.start();resources.add(facade);
            var api=new Http("http://127.0.0.1:"+facade.port()+"/api/v1/ai/","analyst-test-token");
            var budget=new Store.Budget(8,40,500000,new BigDecimal("25"),Instant.now().plusSeconds(150).toString(),3,2,2,1,0,0);
            var workflow=api.call("POST","simulation/workflows",obj("requestId","owner-api-smoke","draftId",draft.path("id"),"populationId",discovery.path("id"),"validationPopulationId",validation.path("id"),"intent","Vary only bureau minimum score within the approved fixture policy; keep offer, underwriting and marketing fixed","lockedStages",List.of("offer","underwriting","marketing"),"budget",budget,"seed",123,"autoExecute",true));
            String workflowId=workflow.path("id").asText();simulations.start();investigations.start();await(simulations,workflowId);
            assertEquals(0,counts.get(Network.FEEDBACK).get());assertTrue(engine.publications().isEmpty(),"Simulation must not publish automatically");
            var completed=api.call("GET","simulation/workflows/"+workflowId,null).path("workflow");
            assertEquals("SIMULATION",completed.path("network").asText());assertFalse(completed.path("evaluations").isEmpty());
            for(var trial:completed.path("evaluations")){assertEquals("COMPLETED",engine.run(trial.path("runId").asText()).path("status").asText());}
            assertEquals(404,assertThrows(Fault.class,()->api.call("GET","feedback/workflows/"+workflowId,null)).status);
            var preview=api.call("POST","simulation/workflows/"+workflowId+"/publication-previews",obj("candidateId",completed.at("/winners/acceptance/candidateId"),"note","Reviewed isolated smoke results and synthetic limitations"));
            var publication=api.call("POST","publication-previews/"+preview.path("previewId").asText()+"/confirm",obj("previewHash",preview.path("previewHash"),"confirm",true));
            assertEquals("PUBLISHED",publication.path("state").asText());assertEquals(1,engine.publications().size());
            String publicationId=central.list("PUBLICATION",null,20).get(0).id();
            var performance=api.call("GET","publications/"+publicationId+"/performance?sourceMode=DEMO",null);assertFalse(performance.isMissingNode());
            int simulationCalls=counts.get(Network.SIMULATION).get();
            var investigation=api.call("POST","publications/"+publicationId+"/investigations",obj("requestId","recorded-smoke","sourceMode","DEMO","text","Explain the recorded performance and whether this customer had an exact personalized offer decision","customerReference",customerId,"budget",budget));
            String investigationId=investigation.path("id").asText();await(investigations,investigationId);
            assertEquals(simulationCalls,counts.get(Network.SIMULATION).get());assertTrue(counts.get(Network.FEEDBACK).get()>=5);
            assertEquals("NOT_RECORDED",api.call("GET","feedback/workflows/"+investigationId,null).at("/workflow/answer/customerTrace/result/eligibility").asText());
            assertEquals(1,api.call("GET","simulation/workflows",null).size());assertEquals(1,api.call("GET","feedback/workflows",null).size());
            assertEquals(1,engine.publications().size(),"Investigation must not publish");
            browser(browser,web,"POST","session/customer",obj("customerId",customerId),csrf);
            assertEquals(8500,browser(browser,web,"GET","customer",null,csrf).at("/profiles/1/data/monthlyIncomeUsd").asInt());
            System.out.println("Owner API smoke passed: customer created/edited/relogged, "+completed.path("evaluations").size()+" real COBOL/HTTP trials, reviewed publication, separate five-agent investigation with exact-customer missing-history check; model replies controlled.");
        }finally{Collections.reverse(resources);for(var resource:resources)try{resource.close();}catch(Exception ignored){}}
    }
    private static JsonNode browser(HttpClient client,String base,String method,String path,JsonNode body,String csrf)throws Exception{
        var request=HttpRequest.newBuilder(URI.create(base+path));if(body!=null){request.header("Content-Type","application/json");request.header("X-CSRF-Token",csrf);}
        var response=client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(write(body))).build(),HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode()<300,response.statusCode()+" "+response.body());return read(response.body());
    }
    private static void await(WorkflowRuntime runtime,String id)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);
        while(System.nanoTime()<deadline){var state=runtime.get(id,"analyst").data();if(state.path("state").asText().equals("COMPLETED"))return;
            assertFalse(Set.of("FAILED","BLOCKED","PAUSED_BUDGET","NO_SUPPORTED_CANDIDATE","NEEDS_INPUT").contains(state.path("state").asText()),state.path("state").asText()+" "+write(state.path("error"))+" "+write(state.path("limitations")));Thread.sleep(100);}
        fail("Workflow did not finish: "+write(runtime.get(id,"analyst").data()));
    }
}
