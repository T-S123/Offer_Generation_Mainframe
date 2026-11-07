package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.lending.ai.Json.*;
import static com.lending.ai.Contracts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in full customer journey through browser/owner/A2A HTTP, real COBOL and disposable PostgreSQL; model replies controlled. */
@Timeout(120)
class CustomerOwnerNetworkSmoke {
    @TempDir Path temp;
    @Test void savedProfileFiveAgentsReviewAndExplicitAcceptance()throws Exception{
        Path root=Path.of("..").toAbsolutePath().normalize(),ready=temp.resolve("ready.json"),log=temp.resolve("owners.log");var resources=new ArrayList<AutoCloseable>();Process fixture=null;
        try(var pg=EmbeddedPostgres.start();var store=new FoundationTest().store()){
            int aiPort;try(var socket=new ServerSocket(0)){aiPort=socket.getLocalPort();}
            String cp=root.resolve("services/target/test-classes")+java.io.File.pathSeparator+root.resolve("services/target/customer-engine-1.0.0.jar")+java.io.File.pathSeparator+System.getProperty("java.class.path");
            var builder=new ProcessBuilder("java","-cp",cp,"com.lending.engine.CustomerNetworkFixture",ready.toString());builder.directory(root.resolve("services").toFile());builder.environment().put("BUREAU_TEST_DATABASE_URL","postgresql://postgres:postgres@127.0.0.1:"+pg.getPort()+"/postgres");builder.environment().put("AI_BASE_PORT",Integer.toString(aiPort));fixture=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);while(!Files.exists(ready)&&fixture.isAlive()&&System.nanoTime()<deadline)Thread.sleep(100);assertTrue(Files.exists(ready),()->{try{return Files.readString(log);}catch(Exception e){return "Owner fixture unavailable";}});
            var config=read(Files.readString(ready));String customer=config.path("customerId").asText();var owner=new EngineGateway("http://127.0.0.1:"+config.path("ownerPort").asInt()+"/api/v1/","fixture-owner");
            var context=owner.get("offer-creation/customers/"+customer+"/personalization-context?profileVersion=2");assertFalse(context.path("offers").isEmpty(),()->"No offers: "+context+" health="+owner.get("offer-creation/health")+" source="+owner.get("offer-source/customer-context/"+customer+"?profileVersion=2"));assertFalse(context.path("previousOffers").isEmpty(),()->"No history: "+context);
            var runtimeRef=new AtomicReference<PersonalizationWorkflowService>();var seen=ConcurrentHashMap.<Role>newKeySet();var peers=new EnumMap<Role,A2AClient>(Role.class);
            for(var role:Network.PERSONALIZATION.roles()){
                var local=new FoundationTest().store();resources.add(local);
                AgentRunner.Tools tools=new AgentRunner.Tools(){public JsonNode descriptors(Role r){return tree(List.of());}public JsonNode call(Role r,Request q,ToolCall t){throw new AssertionError("No uncontrolled tool dispatch");}public void reserve(Request q,String key,long tokens){runtimeRef.get().reserve(role,q,key,tokens);}};
                var agent=new A2AService(role,local,new AgentRunner(Network.PERSONALIZATION,role,(instructions,input,schema)->{
                    seen.add(role);CustomerProposal proposal=null;
                    if(role==Role.DESIGNER){var candidate=input.at("/request/payload/customer/offers/0");var terms=(ObjectNode)candidate.path("original").deepCopy();terms.put("aprPct",terms.path("aprPct").decimalValue().subtract(java.math.BigDecimal.ONE));proposal=new CustomerProposal(candidate.at("/response/id").asText(),terms,tree(List.of()));}
                    return new ModelClient.Reply(tree(new Decision("READY","Open your qualified offer, review the terms, then explicitly confirm.",null,List.of(),List.of(),List.of(),List.of(),null,null,null,null,proposal)),"controlled-fixture",20,20);
                },tools));resources.add(agent);var service=new Server(0,role,"analyst",Map.of("ORCHESTRATOR","delegate"),agent,local,null,null,null,null,null);service.start();resources.add(service);peers.put(role,new A2AClient("http://127.0.0.1:"+service.port(),"delegate"));
            }
            var runtime=new PersonalizationWorkflowService(store,peers,owner);runtimeRef.set(runtime);resources.add(runtime);var simulation=new WorkflowService(store,owner,Map.of());resources.add(simulation);
            var agent=new A2AService(Role.ORCHESTRATOR,store,new AgentRunner(Role.ORCHESTRATOR,(i,p,s)->{throw new AssertionError();},NetworkIsolationTest.unused()));resources.add(agent);
            var facade=new Server(aiPort,Role.ORCHESTRATOR,"analyst",Map.of("analyst","a".repeat(40)),agent,store,simulation,null,null,null,null).personalization(runtime);facade.start();resources.add(facade);runtime.start();
            var browser=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String web="http://127.0.0.1:"+config.path("webPort").asInt()+"/web-api/";
            String csrf=browser(browser,web,"GET","session",null,null).path("csrfToken").asText();browser(browser,web,"POST","session/customer",obj("customerId",customer),csrf);
            assertEquals("NOT_STARTED",browser(browser,web,"GET","customer/personalization?profileVersion=2",null,csrf).path("state").asText());assertTrue(seen.isEmpty());
            var created=browser(browser,web,"POST","customer/personalization",obj("profileVersion",2),csrf);String id=created.path("id").asText();JsonNode completed=created;deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
            while(System.nanoTime()<deadline&&!completed.path("state").asText().equals("COMPLETED")){Thread.sleep(100);completed=browser(browser,web,"GET","customer/personalization/"+id,null,csrf);assertFalse(Set.of("FAILED","PAUSED_BUDGET","NO_QUALIFIED_IMPROVEMENT").contains(completed.path("state").asText()),completed.toString());}
            assertEquals("COMPLETED",completed.path("state").asText(),completed.toString());assertEquals(Set.copyOf(Network.PERSONALIZATION.roles()),seen);assertEquals(0,owner.get("offer-creation/health").path("selections").asInt());
            String offerId=completed.at("/offer/id").asText();var review=browser(browser,web,"GET","customer/offer-review/"+offerId,null,csrf);assertEquals("CUSTOMER_A2A_PERSONALIZED",review.path("personalizationStatus").asText());
            var selection=obj("requestId","explicit-customer-choice","sourceVersion",review.path("sourceVersion"),"kind","PERSONALIZED","terms",review.at("/personalized/terms"));browser(browser,web,"POST","customer/offers/"+offerId+"/selections",selection,csrf);assertEquals(1,owner.get("offer-creation/health").path("selections").asInt());
            assertEquals(id,browser(browser,web,"POST","customer/personalization",obj("profileVersion",2),csrf).path("id").asText());
            var saved=browser(browser,web,"GET","customer",null,csrf);var profile=(ObjectNode)saved.at("/profiles/1/data").deepCopy();profile.put("monthlyIncomeUsd",9600);browser(browser,web,"PUT","customer",obj("expectedVersion",2,"customer",profile),csrf);
            assertEquals("STALE_PROFILE",browser(browser,web,"GET","customer/personalization/"+id,null,csrf).path("state").asText());
            System.out.println("Customer owner smoke passed: created and edited customer, five A2A roles, real COBOL approval and PostgreSQL issuance, browser review/explicit selection, profile-change fencing. No provider or Kafka calls.");
        }finally{Collections.reverse(resources);for(var resource:resources)try{resource.close();}catch(Exception ignored){}if(fixture!=null){try{fixture.getOutputStream().write(10);fixture.getOutputStream().flush();}catch(Exception ignored){}if(!fixture.waitFor(10,TimeUnit.SECONDS))fixture.destroyForcibly();}}
    }
    private static JsonNode browser(HttpClient client,String base,String method,String path,JsonNode body,String csrf)throws Exception{
        var request=HttpRequest.newBuilder(URI.create(base+path));if(body!=null)request.header("Content-Type","application/json").header("X-CSRF-Token",csrf);
        var result=client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(write(body))).build(),HttpResponse.BodyHandlers.ofString());assertTrue(result.statusCode()<300,result.statusCode()+" "+result.body());return read(result.body());
    }
}
