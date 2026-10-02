package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.lending.ai.Json.*;
import static com.lending.ai.Contracts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs six A2A services through automatic/manual modes, internal repair, explicit catalog comparison provenance and idempotent publication. */
@Timeout(45)
class WorkflowIntegrationTest {
    static class Engine extends EngineGateway {
        final Map<String,JsonNode> drafts=new ConcurrentHashMap<>(),runs=new ConcurrentHashMap<>(),receipts=new ConcurrentHashMap<>();
        final AtomicInteger admissions=new AtomicInteger(),publishes=new AtomicInteger();boolean loseSubmit=true,losePublish=true;
        Engine(){super("http://127.0.0.1:1","test");drafts.put("baseline",obj("id","baseline","version",1,"baseline",obj("id","catalog-original"),"campaign",obj("id","campaign"),"policy",obj("version",1),"offer",FoundationTest.baseline().path("offer"),"rules",FoundationTest.baseline().path("rules")));}
        public JsonNode successor(JsonNode publication,String request){return drafts.computeIfAbsent(request,k->obj("id",k,"version",1,"baseline",obj("id",publication.path("catalogOfferId").asText("published-one")),"campaign",obj("id","published-campaign"),"policy",obj("version",1),"offer",publication.path("preview").path("configuration").path("offer"),"rules",publication.path("preview").path("configuration").path("rules")));}
        public JsonNode baseline(String id){return drafts.get(id);}
        public JsonNode population(String id){return obj("id",id,"seed",id.hashCode(),"count",100,"generatorVersion","test");}
        public JsonNode candidate(String workflow,JsonNode source,ObjectNode config,String hash){
            return drafts.computeIfAbsent(workflow+hash,k->obj("id",k,"version",1,"baseline",source.path("baseline"),"campaign",source.path("campaign"),"policy",source.path("policy"),"offer",config.path("offer"),"rules",config.path("rules")));
        }
        public JsonNode submit(String key,JsonNode draft,String population,long seed){
            var r=runs.computeIfAbsent(key,k->{admissions.incrementAndGet();int score=draft.at("/rules/bureau/minimumScore").asInt();int eligible=90-(score-680)/2;double acceptance=.3+(score-680)/100d;
                return obj("id",key,"draft",draft,"status","COMPLETED","report",obj("overall",obj("population",100,"candidate",obj("eligible",eligible,"simulatedExpectedAcceptances",eligible*acceptance))));});
            if(loseSubmit){loseSubmit=false;throw new Fault(503,"DEPENDENCY_UNCERTAIN","Response lost after commit");}return r;
        }
        public JsonNode run(String id){return runs.get(id);}
        public JsonNode publish(String run,String note){
            publishes.incrementAndGet();var result=receipts.computeIfAbsent(run,k->obj("runId",run,"offerId","published-one","campaignId","published-campaign","note",note));
            if(losePublish){losePublish=false;throw new Fault(503,"DEPENDENCY_UNCERTAIN","Response lost after publish");}return result;
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void fullWorkflowPreservesLocksSeparatesWinnersAndRecoversPublication(boolean automatic)throws Exception {
        var stores=new ArrayList<Store>();var services=new ArrayList<A2AService>();var servers=new ArrayList<Server>();var roleCalls=new ConcurrentHashMap<Role,AtomicInteger>();
        var central=new FoundationTest().store();var engine=new Engine();var peers=new EnumMap<Role,A2AClient>(Role.class);
        var workflowRef=new AtomicReference<WorkflowService>();
        try(central){
            for(Role role:Role.values()){
                var local=new FoundationTest().store();stores.add(local);roleCalls.put(role,new AtomicInteger());
                AgentRunner.Tools tools=new AgentRunner.Tools(){
                    public JsonNode descriptors(Role r){return tree(List.of(obj("name","policy.search","inputSchema",Contracts.structure())));}
                    public JsonNode call(Role r,Request q,ToolCall t){return obj("evidence",List.of(obj("evidenceId","approved:0","quote","Approved test fixture")));}
                    public void reserve(Request r,String key,long tokens){workflowRef.get().reserve(role,r,key,tokens);}
                };
                ModelClient model=(instructions,input,schema)->{
                    roleCalls.get(role).incrementAndGet();var payload=input.path("request").path("payload");
                    var context=payload.path("executionContext");
                    if(role==Role.ANALYZER&&payload.has("winnerComparisons"))for(var comparison:payload.path("winnerComparisons")){
                        assertEquals("ORIGINAL_CATALOG_OFFER",comparison.path("comparisonBaseline").asText());
                        assertTrue(Set.of("catalog-original","published-one").contains(comparison.path("catalogBaseline").path("id").asText()));
                        assertEquals(660,comparison.at("/startingDraftConfiguration/rules/underwriting/minimumScore").asInt());
                    }
                    assertEquals("NO_ANALYST_QUESTIONS",context.path("interaction").asText());
                    assertTrue(context.at("/ranking/acceptanceWinnerEligibilityFloorPct").isNumber());
                    assertTrue(context.at("/ranking/eligibilityWinnerAcceptanceFloorPct").isNumber());
                    assertTrue(context.at("/remaining/modelCalls").asInt()>0);
                    assertTrue(context.at("/budget/maxEvaluations").asInt()>0);
                    if(role==Role.REFLECTION&&payload.path("reviewType").asText().equals("PLAN")&&!payload.path("designer").path("stepId").asText().contains("repair"))
                        return new ModelClient.Reply(tree(new Decision("REVISE","Include small-cohort caveats with both denominators",null,List.of(),List.of(),List.of(),List.of(),null)),"test-double",10,10);
                    if(role==Role.RESEARCH&&input.path("observations").isEmpty())return new ModelClient.Reply(tree(new Decision("TOOL","Retrieve policy",null,List.of(),List.of(),List.of(),List.of(),new ToolCall("policy.search","{}"))),"test-double",10,10);
                    var ranges=role==Role.RESEARCH?List.of(new ParameterRegistry.Range("/rules/bureau/minimumScore",BigDecimal.valueOf(680),BigDecimal.valueOf(740),BigDecimal.ONE,null,List.of("approved:0"),false)):List.<ParameterRegistry.Range>of();
                    return new ModelClient.Reply(tree(new Decision("READY","Computed results reviewed",null,List.of("/rules/bureau/minimumScore"),List.of("underwriting"),ranges,List.of(),null)),"test-double",100,50);
                };
                var service=new A2AService(role,local,new AgentRunner(role,model,tools));services.add(service);
                var server=new Server(0,role,"analyst",Map.of("ORCHESTRATOR","test-token"),service,local,null,null,null,null,null);servers.add(server);server.start();
                peers.put(role,new A2AClient("http://127.0.0.1:"+server.port(),"test-token"));
            }
            try(var workflows=new WorkflowService(central,engine,peers)){
                workflowRef.set(workflows);var checked=new AtomicInteger();workflows.evidenceValidator((product,ids)->{assertEquals(List.of("approved:0"),ids);checked.incrementAndGet();});
                var budget=new Store.Budget(10,30,500000,new BigDecimal("25"),Instant.now().plusSeconds(120).toString(),4,3,2,20,0,0);
                var request=obj("requestId","first","draftId","baseline","populationId","discovery","validationPopulationId","validation","intent","Vary only bureau minimum score; do not touch underwriting","lockedStages",List.of("underwriting"),"budget",budget,"seed",123,"autoExecute",automatic);
                String id=workflows.create(request,"analyst").path("id").asText();assertEquals(id,workflows.create(request,"analyst").path("id").asText());workflows.start();
                if(!automatic){await(workflows,id,"AWAITING_SCOPE");var pending=workflows.get(id,"analyst");
                    assertEquals(0,engine.admissions.get());workflows.confirm(id,"analyst",obj("expectedVersion",pending.version(),"scopeHash",pending.data().path("scopeHash")));}
                await(workflows,id,"COMPLETED");var w=workflows.get(id,"analyst");var data=w.data();
                assertFalse(data.has("clarification"));assertEquals(1,data.path("messages").size());
                assertTrue(data.path("steps").has("r1-design-repair-1-a0"));
                assertEquals(0,engine.publishes.get(),"Automatic simulation must not publish");
                assertNotEquals(data.path("winners").path("acceptance").path("candidateId").asText(),data.path("winners").path("eligibility").path("candidateId").asText());
                assertEquals(engine.admissions.get(),data.path("evaluations").size());assertTrue(engine.admissions.get()<=10);assertTrue(checked.get()>=engine.admissions.get());
                for(var c:data.path("candidates"))assertEquals(660,c.at("/configuration/rules/underwriting/minimumScore").asInt());
                for(var role:Role.values())assertTrue(roleCalls.get(role).get()>0,role.name());
                final var version=w.version();assertEquals("SUCCESSOR_REQUIRED",assertThrows(Fault.class,()->workflows.message(id,"analyst",obj("expectedVersion",version,"text","Change underwriting now"))).code);
                var publications=new PublicationService(central,workflows,engine);
                var preview=publications.preview(id,"analyst",obj("candidateId",data.path("winners").path("acceptance").path("candidateId"),"note","Reviewed test results and scope"));
                var confirm=obj("previewHash",preview.path("previewHash"),"confirm",true);String previewId=preview.path("previewId").asText();
                assertEquals("DEPENDENCY_UNCERTAIN",assertThrows(Fault.class,()->publications.confirm(previewId,"analyst",confirm)).code);
                assertEquals("PUBLISHED",publications.confirm(previewId,"analyst",confirm).path("state").asText());publications.confirm(previewId,"analyst",confirm);
                assertEquals(2,engine.publishes.get());assertEquals(1,engine.receipts.size());assertTrue(central.find("PUBLICATION","published-one").isPresent());
                var feedback=new FeedbackService(central,workflows,engine,new OutcomeService(central,null,14));
                var conversations=new ConversationService(central,workflows,publications,feedback);
                var conversation=conversations.create("analyst",obj("requestId","discussion","publicationId","published-one","workflowId",id));
                String cid=conversation.path("id").asText();
                var performance=conversations.message(cid,"analyst",obj("requestId","performance","expectedVersion",1,"text","What is the acceptance rate right now?"));
                assertTrue(performance.path("answer").path("answer").asText().contains("eligible customers"));
                var whyRequest=obj("requestId","why","expectedVersion",2,"text","Why?","budget",budget);
                var why=conversations.message(cid,"analyst",whyRequest);assertEquals(hash(why),hash(conversations.message(cid,"analyst",whyRequest)));
                String investigation=why.path("answer").path("id").asText();await(workflows,investigation,"COMPLETED");
                assertEquals("FEEDBACK",workflows.get(investigation,"analyst").data().path("kind").asText());
                var revised=conversations.message(cid,"analyst",obj("requestId","revise","expectedVersion",3,"text","Change bureau minimum score; do not touch underwriting","budget",budget,"populationId","new-discovery","validationPopulationId","new-validation","lockedStages",List.of(),"autoExecute",false));
                String successor=revised.path("answer").path("id").asText();await(workflows,successor,"AWAITING_SCOPE");
                var successorState=workflows.get(successor,"analyst");
                assertTrue(WorkflowService.strings(successorState.data().path("lockedStages")).contains("underwriting"));
                assertEquals("published-one",successorState.data().path("predecessorPublicationId").asText());
                workflows.confirm(successor,"analyst",obj("expectedVersion",successorState.version(),"scopeHash",successorState.data().path("scopeHash")));
                await(workflows,successor,"COMPLETED");
                assertTrue(workflows.evaluations(id,"analyst",0,2).path("nextOffset").isInt());
                String ref=data.path("evaluations").path(0).path("reportRef").asText();
                assertTrue(workflows.artifact(id,"analyst",ref).has("overall"));
                assertThrows(Fault.class,()->workflows.artifact(id,"other-analyst",ref));
                assertFalse(workflows.view(w).path("workflow").has("explorationPlan"));
                var next=request.deepCopy();next.put("requestId","second");assertEquals("VALIDATION_LEAKAGE",assertThrows(Fault.class,()->workflows.create(next,"analyst")).code);
            }
        }finally{for(var s:servers)s.close();for(var a:services)a.close();for(var s:stores)s.close();}
    }
    static void await(WorkflowService service,String id,String state)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);JsonNode data=null;
        while(System.nanoTime()<end){data=service.get(id,"analyst").data();if(data.path("state").asText().equals(state))return;if(Set.of("FAILED","PAUSED_BUDGET","NEEDS_INPUT","BLOCKED","NO_SUPPORTED_CANDIDATE").contains(data.path("state").asText()))fail(write(data));Thread.sleep(40);}
        fail("Timed out waiting for "+state+": "+write(data));
    }
}
