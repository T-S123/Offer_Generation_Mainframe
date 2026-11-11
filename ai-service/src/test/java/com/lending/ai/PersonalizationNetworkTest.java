package com.lending.ai;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import static com.lending.ai.Contracts.*;
import static com.lending.ai.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP/A2A tests exercise offer readiness independently of delivery, zero/six exchanges, refinement, and session and profile fencing. */
@Timeout(60)
class PersonalizationNetworkTest {
    @TempDir Path root;
    static class Owner extends EngineGateway {
        int version=2,issued,assessed;boolean withdrawn;boolean rejectFirst=true;volatile boolean offersReady;final List<String> commands=new ArrayList<>();
        Owner(){super("http://127.0.0.1:1/","unused");}
        public JsonNode get(String path){
            if(path.contains("personalization-sessions"))return obj("active",true);
            if(path.startsWith("customers/"))return obj("profiles",List.of(obj("version",version)));
            if(path.contains("/session-offers/quote-1")){if(withdrawn)throw new Fault(409,"OFFER_CHANGED","Expired");return obj("id","quote-1","offer",obj("id","response-1","personalized",obj("terms",terms())));}
            if(path.contains("personalization-context"))return obj("customerId","customer-1","profileVersion",version,"pipeline",obj("state","OFFERS_PENDING"),"offersReady",offersReady,"personalizationPolicy",obj("version","CUSTOMER-2026-001"),"offers",List.of(obj("response",obj("id","response-1","version",3),"policyVersion","terms-v1","original",terms())));
            throw new AssertionError(path);
        }
        public JsonNode post(String path,JsonNode body){commands.add(path);assertTrue(path.startsWith("offer-creation/customers/customer-1/"));assertEquals(version,body.path("profileVersion").asInt());
            if(path.endsWith("personalization-candidates")){assessed++;return obj("metricId","qualified-metric","approval",obj("id","approval-1","approved",!rejectFirst||assessed>1,"terms",terms()),"improvesFit",true);}
            if(path.contains("/session-offers?")){issued++;return obj("id","quote-1","offer",obj("id","response-1","personalized",obj("terms",terms())));}throw new AssertionError(path);
        }
    }
    static JsonNode terms(){return obj("amountUsd",10000,"aprPct",10,"annualFeeUsd",0,"termMonths",36);}
    @Test void fiveAgentsReviseRejectedTermsIssueOnceAndNeverAccept()throws Exception{exercise(false);}
    @Test void sixCustomerExchangesResumeAndRefineBeforeIssuing()throws Exception{exercise(true);}
    void exercise(boolean conversation)throws Exception{
        var resources=new ArrayList<AutoCloseable>();var peers=new EnumMap<Role,A2AClient>(Role.class);var boxes=new EnumMap<Role,AgentToolbox>(Role.class);var seen=new HashSet<Role>();var facade=new AtomicReference<Server>();var owner=new Owner();if(conversation)owner.rejectFirst=false;
        try(var store=new FoundationTest().store()){
            for(var role:Network.PERSONALIZATION.roles()){
                var local=new FoundationTest().store();resources.add(local);var toolbox=new AgentToolbox(null,new McpTools(root.resolve("none.json"),Network.PERSONALIZATION),(q,k,t)->new Http("http://127.0.0.1:"+facade.get().port(),"agent-"+role).call("POST","internal/budget",obj("request",q,"callId",k,"maxTokens",t))).settlement((q,k,t)->new Http("http://127.0.0.1:"+facade.get().port(),"agent-"+role).call("POST","internal/usage",obj("request",q,"callId",k,"actualTokens",t)));boxes.put(role,toolbox);
                var agent=new A2AService(role,local,new AgentRunner(Network.PERSONALIZATION,role,(instructions,input,schema)->{
                    assertFalse(input.path("tools").toString().contains("registry.describe"));
                    assertEquals(0, schema.at("/properties/allowedParameterIds/maxItems").intValue());
                    assertTrue(schema.at("/properties/allowedParameterIds/maxItems").isIntegralNumber());
                    if (role != Role.DESIGNER) assertEquals("null", schema.at("/properties/customerProposal/type").asText());
                    seen.add(role);assertEquals("PERSONALIZATION",input.at("/request/payload/network").asText());if(role!=Role.ORCHESTRATOR)assertFalse(input.path("observations").isEmpty());if(role==Role.DESIGNER)assertTrue(input.at("/request/payload/contextAnalysis/summary").isTextual());
                    var payload=input.at("/request/payload");int answers=payload.path("answeredDecisions").size();String stage=payload.path("stage").asText();
                    if(conversation&&role==Role.ORCHESTRATOR&&(stage.equals("INITIAL_REVIEW")&&answers<3||stage.equals("CANDIDATE_REVIEW")&&answers<6)){
                        assertEquals(6-answers,payload.at("/executionContext/conversation/roundsRemaining").asInt());
                        var question=new Question("preference-"+answers,"TRADEOFF","Preference bundle "+answers+": how should the payment and total cost change?","This changes candidate terms",List.of(),List.of());
                        return new ModelClient.Reply(tree(new Decision("NEEDS_INPUT","Discuss the offer",null,List.of(),List.of(),List.of(),List.of(),null,question,null)),"fixture",30,20);
                    }
                    if(conversation&&role==Role.ORCHESTRATOR&&stage.equals("CANDIDATE_REVIEW")&&payload.path("previousCandidates").size()==1)
                        return new ModelClient.Reply(tree(new Decision("REVISE","Use the customer's lower-payment preference in another candidate",null,List.of(),List.of(),List.of(),List.of(),null)),"fixture",30,20);
                    var d=new Decision("READY","Review the qualified terms, then explicitly confirm your choice.",null,List.of(),List.of(),List.of(),List.of(),null,null,null,null,role==Role.DESIGNER?new CustomerProposal("response-1",terms(),tree(List.of())):null);
                    return new ModelClient.Reply(tree(d),"fixture",30,20);
                },toolbox));resources.add(agent);var server=new Server(0,role,"analyst",Map.of("ORCHESTRATOR","delegate"),agent,local,null,null,null,null,null);resources.add(server);server.start();peers.put(role,new A2AClient("http://127.0.0.1:"+server.port(),"delegate"));
            }
            var runtime=new PersonalizationWorkflowService(store,peers,owner);resources.add(runtime);var simulation=new WorkflowService(store,owner,Map.of());resources.add(simulation);var a2a=new A2AService(Role.ORCHESTRATOR,store,new AgentRunner(Role.ORCHESTRATOR,(i,p,s)->{throw new AssertionError();},NetworkIsolationTest.unused()));resources.add(a2a);
            var credentials=new HashMap<String,String>();credentials.put("analyst","business-token");credentials.put("DESIGNER","simulation-token");for(var role:Network.PERSONALIZATION.roles())credentials.put("PERSONALIZATION_"+role,"agent-"+role);
            var server=new Server(0,Role.ORCHESTRATOR,"analyst",credentials,a2a,store,simulation,null,null,null,null).personalization(runtime);resources.add(server);server.start();facade.set(server);for(var role:boxes.keySet())boxes.get(role).personalization(new Http("http://127.0.0.1:"+server.port(),"agent-"+role));
            var client=new Http("http://127.0.0.1:"+server.port(),"business-token");String path="api/v1/ai/customer-personalization/customer-1";
            assertEquals("NOT_STARTED",client.call("GET",path+"?profileVersion=2&sessionId=test-session-123456",null).path("state").asText());assertTrue(seen.isEmpty());var created=client.call("POST",path,obj("profileVersion",2,"sessionId","test-session-123456","message","Can my offer better fit my needs?"));String id=created.path("id").asText();assertEquals(id,client.call("POST",path,obj("profileVersion",2,"sessionId","test-session-123456","message","Can my offer better fit my needs?")).path("id").asText());runtime.start();
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(35);
            while(System.nanoTime()<deadline&&!runtime.customerView("customer-1",id).path("summary").isTextual())Thread.sleep(50);
            assertEquals("Waiting for qualification and offer preparation to finish.",runtime.customerView("customer-1",id).path("summary").asText());
            assertTrue(seen.isEmpty());owner.offersReady=true;
            while(System.nanoTime()<deadline&&!runtime.get(id,"customer:customer-1").data().path("state").asText().equals("COMPLETED")){var pending=runtime.customerView("customer-1",id);if(pending.path("state").asText().equals("NEEDS_INPUT")){assertTrue(conversation);runtime.reply("customer-1",id,obj("expectedVersion",pending.path("version"),"questionId",pending.at("/pendingQuestion/id"),"text","Keep the amount; prioritize affordable payments. Answer "+pending.at("/conversation/roundsUsed").asInt()));}Thread.sleep(50);}
            var result=client.call("GET",path+"/"+id+"?sessionId=test-session-123456",null);assertEquals("COMPLETED",result.path("state").asText(),result.toString());assertEquals(conversation?6:0,result.at("/conversation/roundsUsed").asInt());assertEquals(Set.copyOf(Network.PERSONALIZATION.roles()),seen);assertEquals(2,owner.assessed);assertEquals(1,owner.issued);assertEquals("OPEN_OFFER",result.at("/actions/0/type").asText());assertFalse(owner.commands.stream().anyMatch(p->p.contains("selections")||p.contains("business")));
            assertEquals(404,assertThrows(Fault.class,()->client.call("GET","api/v1/ai/customer-personalization/other/"+id,null)).status);client.call("POST",path,obj("profileVersion",2,"sessionId","test-session-123456","message","Can my offer better fit my needs?"));assertEquals(1,owner.issued);
            assertThrows(Fault.class,()->client.call("GET",path+"/"+id+"?sessionId=another-session-123",null));
            owner.withdrawn=true;assertEquals("OFFER_CHANGED",client.call("GET",path+"/"+id+"?sessionId=test-session-123456",null).path("state").asText());owner.withdrawn=false;
            owner.version=3;assertEquals("STALE_PROFILE",client.call("GET",path+"/"+id+"?sessionId=test-session-123456",null).path("state").asText());assertThrows(Fault.class,()->client.call("POST",path,obj("profileVersion",2,"sessionId","test-session-123456","message","Can my offer better fit my needs?")));
        }finally{Collections.reverse(resources);for(var resource:resources)resource.close();}
    }
}
