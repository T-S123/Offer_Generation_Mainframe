package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import static org.junit.jupiter.api.Assertions.*;
import static com.lending.ai.Contracts.*;
import static com.lending.ai.Json.*;

/** Real HTTP A2A peers verify durable replies, interactive checkpoints, shared budgets and immutable follow-up history. */
@Timeout(60)
class WorkflowClarificationTest {
    static Decision question(String key,String topic){return new Decision("NEEDS_INPUT","A material decision is needed",null,List.of(),List.of(),List.of(),List.of(),null,
        new Question(key,topic,"Should we continue or explore a revised bureau range?","Your decision determines the next experiment; existing results remain available.",List.of("Continue with the present scope","Explore bureau score 700 to 740"),List.of()),null);}
    static boolean answered(JsonNode p,String key){for(var a:p.path("answeredDecisions"))if(a.at("/question/decisionKey").asText().equals(key))return true;return false;}
    static Decision ready(Role role,JsonNode p){
        var ranges=role==Role.RESEARCH?List.of(new ParameterRegistry.Range("/rules/bureau/minimumScore",new BigDecimal(answered(p,"next-range")?"700":"680"),new BigDecimal("740"),BigDecimal.ONE,null,List.of("approved:0"),false)):List.<ParameterRegistry.Range>of();
        return new Decision("READY","Reviewed with the analyst's decision",null,List.of("/rules/bureau/minimumScore"),List.of("underwriting"),ranges,List.of(),null);
    }
    static final class Harness implements AutoCloseable {
        final Store store=new FoundationTest().store();final WorkflowIntegrationTest.Engine engine=new WorkflowIntegrationTest.Engine(){
            final Map<String,JsonNode> populations=new ConcurrentHashMap<>();
            public JsonNode generatePopulation(String key,int count,long seed){return populations.computeIfAbsent(key,k->obj("id",k,"seed",seed,"count",count,"generatorVersion","test"));}
        };
        final List<Store> stores=new ArrayList<>();final List<A2AService> services=new ArrayList<>();final List<Server> servers=new ArrayList<>();final Map<Role,A2AClient> peers=new EnumMap<>(Role.class);
        final List<Request> requests=new CopyOnWriteArrayList<>();final AtomicReference<WorkflowService> reference=new AtomicReference<>();WorkflowService workflows;
        Harness(BiFunction<Role,JsonNode,Decision> decide)throws Exception{
            for(var role:Role.values()){
                var local=new FoundationTest().store();stores.add(local);
                AgentRunner.Tools tools=new AgentRunner.Tools(){
                    public JsonNode descriptors(Role r){return tree(List.of());}
                    public List<JsonNode> initialObservations(Role r,Request q){return r==Role.RESEARCH?List.of(obj("result",obj("evidence",List.of(obj("evidenceId","approved:0","quote","Approved fixture"))))):List.of();}
                    public JsonNode call(Role r,Request q,ToolCall t){throw new AssertionError("Unexpected tool");}
                    public void reserve(Request q,String key,long tokens){reference.get().reserve(role,q,key,tokens);}
                    public void settle(Request q,String key,long tokens){reference.get().settle(role,q,key,tokens);}
                };
                var agent=new A2AService(role,local,new AgentRunner(role,(i,input,s)->{var request=convert(input.path("request"),Request.class);requests.add(request);assertEquals("MATERIAL_DECISIONS_ONLY",request.payload().at("/executionContext/interaction").asText());return new ModelClient.Reply(tree(decide.apply(role,request.payload())),"test-double",100,50);},tools));services.add(agent);
                var server=new Server(0,role,"analyst",Map.of("ORCHESTRATOR","token"),agent,local,null,null,null,null,null);servers.add(server);server.start();peers.put(role,new A2AClient("http://127.0.0.1:"+server.port(),"token"));
            }restart();
        }
        void restart(){if(workflows!=null)workflows.close();workflows=new WorkflowService(store,engine,peers);reference.set(workflows);workflows.start();}
        String create(int trials){return workflows.create(obj("requestId","interactive","draftId","baseline","populationId","discovery","validationPopulationId","validation","intent","Explore bureau score; preserve underwriting","lockedStages",List.of("underwriting"),"budget",new Store.Budget(trials,60,2000000,new BigDecimal("100"),Instant.now().plusSeconds(120).toString(),4,3,2,5,0,0)),"analyst").path("id").asText();}
        Store.Saved waitFor(String id,String state)throws Exception{WorkflowIntegrationTest.await(workflows,id,state);return workflows.get(id,"analyst");}
        JsonNode reply(Store.Saved w,String text){return workflows.message(w.id(),"analyst",obj("expectedVersion",w.version(),"questionId",w.data().at("/pendingQuestion/id"),"text",text));}
        public void close(){workflows.close();servers.forEach(Server::close);services.forEach(A2AService::close);stores.forEach(Store::close);store.close();}
    }
    @Test void pausesAtPlanningDiscoveryAndFinalReviewWithoutReplayingTrials()throws Exception{
        try(var h=new Harness((role,p)->{
            String key=role==Role.ORCHESTRATOR?"scope-choice":role==Role.ANALYZER&&p.path("analysisMode").asText().equals("INTERIM_SIMULATION")?"discovery-choice":role==Role.REFLECTION&&p.path("reviewType").asText().equals("RESULTS")?"interpretation-choice":null;
            // Prompts differ so duplicate-question protection does not confuse distinct decisions.
            if(key!=null&&!answered(p,key)){var d=question(key,key.startsWith("scope")?"SCOPE":"INTERPRETATION");var q=d.question();return new Decision(d.status(),d.summary(),null,List.of(),List.of(),List.of(),List.of(),null,new Question(q.decisionKey(),q.topic(),key+": "+q.prompt(),q.why(),q.choices(),q.metricIds()),null);}return ready(role,p);
        })){
            String id=h.create(20);var scope=h.waitFor(id,"NEEDS_INPUT");assertEquals(0,h.engine.admissions.get());
            String task=scope.data().at("/pendingQuestion/taskId").asText(),step=scope.data().at("/pendingQuestion/stepKey").asText();
            int calls=h.requests.size();h.restart();Thread.sleep(100);assertEquals(calls,h.requests.size());
            h.workflows.mutate(id,n->((ObjectNode)n.path("pendingQuestion")).put("askedAt",Instant.now().minusSeconds(180).toString()));scope=h.workflows.get(id,"analyst");
            var deadline=Instant.parse(scope.data().at("/budget/deadline").asText());var body=obj("expectedVersion",scope.version(),"questionId",scope.data().at("/pendingQuestion/id"),"text","Continue with bureau only");
            assertThrows(Fault.class,()->h.workflows.message(id,"another-analyst",body));
            var reply=h.workflows.message(id,"analyst",body);assertTrue(Instant.parse(reply.at("/workflow/budget/deadline").asText()).isAfter(deadline.plusSeconds(179)));
            assertEquals(reply.at("/workflow/budget/deadline"),h.workflows.message(id,"analyst",body).at("/workflow/budget/deadline"));
            var changed=body.deepCopy();changed.put("text","Different answer");assertEquals("ANSWER_CONFLICT",assertThrows(Fault.class,()->h.workflows.message(id,"analyst",changed)).code);
            var discovery=h.waitFor(id,"NEEDS_INPUT");assertEquals("discovery-choice",discovery.data().at("/pendingQuestion/question/decisionKey").asText());assertTrue(h.engine.admissions.get()>0);
            assertEquals(task,h.store.get("STEP",step).data().path("taskId").asText());assertEquals(1,h.store.get("STEP",step).data().path("turns").size());
            var conversations=new ConversationService(h.store,h.workflows,null,null);
            String conversation=conversations.create("analyst",obj("requestId","interactive-conversation","workflowId",id)).path("id").asText();
            var message=obj("requestId","reply-one","expectedVersion",1,"operation","CLARIFY","workflowVersion",discovery.version(),"questionId",discovery.data().at("/pendingQuestion/id"),"text","Continue within this scope");
            var receipt=conversations.message(conversation,"analyst",message);assertEquals(hash(receipt),hash(conversations.message(conversation,"analyst",message)));
            var review=h.waitFor(id,"NEEDS_INPUT");assertEquals("interpretation-choice",review.data().at("/pendingQuestion/question/decisionKey").asText());
            int completed=h.engine.admissions.get();var stale=obj("expectedVersion",review.version(),"questionId","old-question","text","Continue");assertEquals("STALE_QUESTION",assertThrows(Fault.class,()->h.workflows.message(id,"analyst",stale)).code);
            h.reply(review,"Report the separate winners with the limitations");var done=h.waitFor(id,"COMPLETED");assertEquals(completed,h.engine.admissions.get());assertEquals(0,h.engine.publishes.get());assertEquals(3,done.data().path("answers").size());assertFalse(done.data().has("pendingQuestion"));assertEquals(20,h.store.budget(id).at("/limits/maxEvaluations").asInt());assertEquals(completed,done.data().path("evaluations").size());
        }
    }
    @Test void changedScopeCreatesLinkedExperimentWithFreshPopulationsAndOneBudget()throws Exception{
        try(var h=new Harness((role,p)->{
            if(role==Role.ANALYZER&&p.path("analysisMode").asText().equals("SIMULATION")){
                if(!answered(p,"next-range"))return question("next-range","NEXT_EXPERIMENT");
                if(p.has("userAnswer"))return new Decision("FOLLOW_UP","Analyst requested a narrower range",null,List.of(),List.of(),List.of(),List.of(),null,null,"Explore only bureau score 700 to 740; keep all other fields fixed");
            }return ready(role,p);
        })){
            String id=h.create(20);var pending=h.waitFor(id,"NEEDS_INPUT");String original=hash(pending.data().path("evaluations"));int prior=h.engine.admissions.get();
            h.reply(pending,"Explore only bureau score 700 to 740");var done=h.waitFor(id,"COMPLETED");assertEquals(2,done.data().path("revision").asInt());assertEquals(1,done.data().path("experiments").size());
            var archive=h.workflows.artifact(id,"analyst",done.data().at("/experiments/0/resultRef").asText());assertEquals(original,hash(archive.path("evaluations")));assertEquals(prior,archive.path("evaluations").size());
            assertNotEquals(archive.path("validationPopulationId"),done.data().path("validationPopulationId"));assertNotEquals(archive.path("populationId"),done.data().path("populationId"));
            assertEquals(h.engine.admissions.get(),h.store.budget(id).path("evaluations").asInt());assertTrue(h.engine.admissions.get()<=20);assertEquals(h.engine.admissions.get(),h.workflows.evaluations(id,"analyst",0,20).path("total").asInt());
            for(var trial:done.data().path("evaluations"))assertEquals(660,trial.at("/configuration/rules/underwriting/minimumScore").asInt());assertEquals(0,h.engine.publishes.get());assertEquals(1,h.store.list("WORKFLOW","",100).size());
        }
    }
    @Test void repeatedQuestionIsRepairedWithoutPromptingAgain(){
        var first=question("scope-choice","SCOPE");var calls=new java.util.concurrent.atomic.AtomicInteger();
        var runner=new AgentRunner(Role.ORCHESTRATOR,(i,p,s)->new ModelClient.Reply(tree(calls.incrementAndGet()==1?first:ready(Role.ORCHESTRATOR,p)),"test",1,1),A2AProtocolTest.tools());
        var q=new Request("1.0.0","repeat","workflow","step",1,Instant.now().plusSeconds(60).toString(),obj("answeredDecisions",List.of(obj("question",first.question(),"text","Use bureau"))));
        assertEquals("READY",runner.run(q,()->false).decision().status());assertEquals(2,calls.get());
    }
    @Test void cancelWhileWaitingRejectsAnswerWithoutExtendingBudget()throws Exception{
        try(var h=new Harness((role,p)->question("scope-choice","SCOPE"))){String id=h.create(20);h.waitFor(id,"NEEDS_INPUT");var limits=h.store.budget(id).path("limits");h.workflows.cancel(id,"analyst");var canceled=h.workflows.get(id,"analyst");assertThrows(Fault.class,()->h.reply(canceled,"Continue"));assertEquals(limits,h.store.budget(id).path("limits"));assertEquals(0,h.engine.admissions.get());}
    }
    @Test void failedContinuationRetriesWithItsAnswerInsteadOfReasking()throws Exception{
        var failed=new java.util.concurrent.atomic.AtomicBoolean();
        try(var h=new Harness((role,p)->{
            if(role==Role.RESEARCH){if(!answered(p,"scope-choice"))return question("scope-choice","SCOPE");assertTrue(p.has("userAnswer"));assertEquals("approved:0",p.at("/previousObservations/0/result/evidence/0/evidenceId").asText());if(failed.compareAndSet(false,true))throw new Fault(503,"TEST_INTERRUPTED","Simulated failed continuation");}
            return ready(role,p);
        })){
            String id=h.create(20);h.reply(h.waitFor(id,"NEEDS_INPUT"),"Continue with bureau only");var done=h.waitFor(id,"COMPLETED");
            assertEquals(1,done.data().path("answers").size());assertTrue(h.requests.stream().anyMatch(r->r.stepId().endsWith("research-a1")&&r.payload().has("userAnswer")));
        }
    }
    @Test void followUpPausesAtRemainingTrialCapAndResumesOnlyAfterExplicitExtension()throws Exception{
        try(var h=new Harness((role,p)->{
            if(role==Role.ANALYZER&&p.path("analysisMode").asText().equals("SIMULATION")){
                if(!answered(p,"next-range"))return question("next-range","NEXT_EXPERIMENT");
                if(p.has("userAnswer"))return new Decision("FOLLOW_UP","Analyst requested a narrower range",null,List.of(),List.of(),List.of(),List.of(),null,null,"Explore bureau score 700 to 740");
            }return ready(role,p);
        })){
            String id=h.create(10);h.reply(h.waitFor(id,"NEEDS_INPUT"),"Explore bureau score 700 to 740");var paused=h.waitFor(id,"PAUSED_BUDGET");
            assertEquals("FOLLOW_UP_PENDING",paused.data().path("resumeState").asText());assertEquals(1,paused.data().path("revision").asInt());
            int prior=h.engine.admissions.get();assertTrue(10-prior<4);assertEquals(10,h.store.budget(id).at("/limits/maxEvaluations").asInt());
            var budget=(ObjectNode)paused.data().path("budget").deepCopy();budget.put("maxEvaluations",20).put("deadline",Instant.parse(budget.path("deadline").asText()).plusSeconds(60).toString());
            h.workflows.resume(id,"analyst",obj("expectedVersion",paused.version(),"budget",budget));var done=h.waitFor(id,"COMPLETED");
            assertEquals(2,done.data().path("revision").asInt());assertEquals(prior,done.data().at("/experiments/0/trialCount").asInt());assertTrue(h.engine.admissions.get()<=20);
        }
    }
}
