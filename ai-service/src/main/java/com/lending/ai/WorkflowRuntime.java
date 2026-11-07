package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static com.lending.ai.Json.*;
import static com.lending.ai.Contracts.*;

/** Shared durable task mechanics; each network owns its worker, agents and authorized workflow slice. */
public abstract class WorkflowRuntime implements AutoCloseable {
    protected final Store store;protected final Map<Role,A2AClient> agents;public final Network network;
    private final ScheduledExecutorService worker;protected volatile boolean closed;
    protected static final class Yield extends RuntimeException { }
    protected WorkflowRuntime(Store store,Map<Role,A2AClient> agents,Network network){this.store=store;this.agents=Map.copyOf(agents);this.network=network;
        worker=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"ai-"+network.name().toLowerCase(Locale.ROOT)+"-workflows"));}
    public void start(){worker.scheduleWithFixedDelay(this::tick,0,1,TimeUnit.SECONDS);}
    protected abstract String initialState();
    protected abstract Set<String> activeStates();
    protected abstract void executeState(String id,String state);
    protected abstract JsonNode executionContext(JsonNode workflow);
    public abstract JsonNode create(JsonNode body,String analyst);
    public JsonNode confirm(String id,String analyst,JsonNode body){get(id,analyst);throw new Fault(422,"NETWORK_OPERATION","Scope confirmation belongs to simulation");}
    public boolean owns(JsonNode data){return Network.workflow(data)==network;}
    protected Store.Saved owned(String id){var w=store.get("WORKFLOW",id);if(!owns(w.data()))throw new Fault(404,"NOT_FOUND","Workflow not found in this network");return w;}
    public Store.Saved get(String id,String analyst){var w=owned(id);if(!w.data().path("analyst").asText().equals(analyst))throw new Fault(404,"NOT_FOUND","Workflow not found");return w;}
    /** Filters before filling a page, so interleaved legacy records cannot hide later matching workflows. */
    public List<Store.Saved> list(String analyst,String after,int limit){return WorkflowRouter.list(store,analyst,after,limit,network);}
    /** Returns a bounded projection; full trial configurations and immutable reports are separately paginated. */
    public JsonNode view(Store.Saved saved){
        require(owns(saved.data()),"NETWORK_SCOPE","Workflow belongs to another network");var data=(ObjectNode)saved.data().deepCopy();data.put("network",network.name());data.remove(List.of("explorationPlan","refinementPlan","refinementPlan0","refinementPlan1","validationPlan","candidates","baselineDraft"));
        var steps=object();saved.data().path("steps").fields().forEachRemaining(e->steps.set(e.getKey(),obj("role",e.getValue().path("role"),"taskId",e.getValue().path("taskId"),"resultRef",store.artifact((Object)e.getValue().path("result")))));data.set("steps",steps);
        var trials=MAPPER.createArrayNode();for(var e:saved.data().path("evaluations"))trials.add(obj("candidateId",e.path("candidateId"),"phase",e.path("phase"),"status",e.path("status"),"runId",e.path("runId"),"score",e.path("score"),"reportRef",e.path("reportRef")));data.set("evaluations",trials);
        return obj("id",saved.id(),"version",saved.version(),"workflow",data,"usage",store.budget(saved.id()));
    }
    public JsonNode evaluations(String id,String analyst,int offset,int limit){
        var w=get(id,analyst).data();var rows=MAPPER.createArrayNode();
        for(var experiment:w.path("experiments"))for(var row:store.artifact(experiment.path("resultRef").asText()).path("evaluations"))rows.add(((ObjectNode)row.deepCopy()).put("experimentRevision",experiment.path("revision").asInt()));
        for(var row:w.path("evaluations"))rows.add(((ObjectNode)row.deepCopy()).put("experimentRevision",w.path("revision").asInt()));
        require(offset>=0&&offset<=rows.size()&&limit>0&&limit<=20,"PAGE_LIMIT","Use a valid offset and limit 1-20");
        var items=MAPPER.createArrayNode();for(int i=offset;i<Math.min(offset+limit,rows.size());i++)items.add(rows.get(i));
        return obj("items",items,"offset",offset,"total",rows.size(),"nextOffset",offset+items.size()<rows.size()?offset+items.size():null);
    }
    /** Resolves only content explicitly referenced by this analyst's workflow, including delegated results. */
    public JsonNode artifact(String id,String analyst,String ref){
        var w=get(id,analyst).data();boolean found=references(w,ref);
        for(var experiment:w.path("experiments"))if(references(store.artifact(experiment.path("resultRef").asText()),ref))found=true;
        for(var step:w.path("steps"))if(("sha256:"+hash(step.path("result"))).equals(ref))found=true;
        if(!found)throw new Fault(404,"NOT_FOUND","Artifact not found in this workflow");return store.artifact(ref);
    }
    private static boolean references(JsonNode n,String ref){if(n.isTextual())return n.asText().equals(ref);for(var child:n)if(references(child,ref))return true;return false;}
    /** Answers a specific pending question once, or revises an unexecuted stopped request for older clients. */
    public synchronized JsonNode message(String id,String analyst,JsonNode body){
        fields(body,"expectedVersion","text","questionId");var w=get(id,analyst);
        if(body.has("questionId"))return answer(w,body);
        require(!w.data().has("pendingQuestion"),"QUESTION_ID_REQUIRED","Read the current question and submit its questionId with your answer");
        require(w.version()==body.path("expectedVersion").asInt(),"STALE_VERSION","Reload conversation");
        require(w.data().path("evaluations").isEmpty()&&!w.data().has("approvedAt"),"SUCCESSOR_REQUIRED","An executed experiment is immutable; create a successor workflow to change scope");
        require(Set.of("NEEDS_INPUT","AWAITING_SCOPE","REVIEW_REQUIRED","FAILED","BLOCKED","NO_SUPPORTED_CANDIDATE").contains(w.data().path("state").asText()),"WRONG_STATE","Cancel an active experiment before revising its plan");
        String message=text(body,"text");require(message.length()<=6000,"MESSAGE_LIMIT","Message too long");var n=(ObjectNode)w.data().deepCopy();
        ((ArrayNode)n.path("messages")).add(obj("role","analyst","text",message));n.put("intent",n.path("intent").asText()+"\n"+message);
        require(n.path("intent").asText().length()<=12000,"CONVERSATION_LIMIT","Create a successor workflow for further changes");
        n.put("revision",n.path("revision").asInt()+1).put("state",initialState());n.remove(List.of("scopeHash","ranges","allowedParameterIds","error","analysis","reflection","clarification","outcome"));
        revised(n);return view(store.update("WORKFLOW",id,w.version(),n));
    }
    /** Lets a network discard its derived plan when an unexecuted stopped request is explicitly revised. */
    protected void revised(ObjectNode workflow){}
    /** Persists the answer and exact wait duration together; a replay neither changes the answer nor extends time twice. */
    private JsonNode answer(Store.Saved saved,JsonNode body){
        String questionId=text(body,"questionId"),reply=text(body,"text");require(reply.length()<=6000,"MESSAGE_LIMIT","Answer too long");
        for(var prior:saved.data().path("answers"))if(prior.path("questionId").asText().equals(questionId)){
            require(prior.path("text").asText().equals(reply),"ANSWER_CONFLICT","This question already has a different answer");return view(saved);
        }
        require(saved.version()==body.path("expectedVersion").asInt(),"STALE_VERSION","Reload the current question before answering");
        var w=saved.data();var question=w.path("pendingQuestion");
        require(w.path("state").asText().equals("NEEDS_INPUT")&&question.path("id").asText().equals(questionId),"STALE_QUESTION","That question is no longer waiting for input");
        var now=Instant.now();var wait=Duration.between(Instant.parse(question.path("askedAt").asText()),now);if(wait.isNegative())wait=Duration.ZERO;
        var n=(ObjectNode)w.deepCopy();var budget=(ObjectNode)n.path("budget");budget.put("deadline",Instant.parse(budget.path("deadline").asText()).plus(wait).toString());
        n.withArray("answers").add(obj("questionId",questionId,"stepKey",question.path("stepKey"),"question",question.path("question"),"text",reply,"answeredAt",now.toString(),"waitMillis",wait.toMillis()));
        if(n.has("scope")){((ObjectNode)n.path("scope")).set("budget",budget.deepCopy());n.put("scopeHash",hash(n.path("scope")));}
        n.put("state",question.path("resumeState").asText()).put("lastActivity","Answer received; the requesting agent is resuming.");n.remove(List.of("pendingQuestion","clarification"));
        return view(store.answer(saved.id(),saved.version(),n));
    }
    /** Resumes the same saved plan after the analyst explicitly increases its resource limits. */
    public synchronized JsonNode resume(String id,String analyst,JsonNode body){
        fields(body,"expectedVersion","budget");var saved=get(id,analyst);require(saved.version()==body.path("expectedVersion").asInt(),"STALE_VERSION","Reload the paused workflow");
        require(saved.data().path("state").asText().equals("PAUSED_BUDGET"),"WRONG_STATE","Only budget-paused work can be resumed");
        var budget=convert(body.path("budget"),Store.Budget.class);store.extend(id,budget);var n=(ObjectNode)saved.data().deepCopy();
        n.set("budget",tree(budget));n.put("budgetRevision",n.path("budgetRevision").asInt()+1).put("budgetApprovedBy",analyst).put("budgetApprovedAt",Instant.now().toString());
        if(n.has("scope")){((ObjectNode)n.path("scope")).set("budget",tree(budget));n.put("scopeHash",hash(n.path("scope")));}
        n.put("state",n.path("resumeState").asText());n.remove("error");return view(store.update("WORKFLOW",id,saved.version(),n));
    }
    public JsonNode cancel(String id,String analyst){
        var w=get(id,analyst);var n=(ObjectNode)w.data().deepCopy();n.put("state","CANCELED").put("stopReason","ANALYST_CANCELED");
        var result=store.update("WORKFLOW",id,w.version(),n);
        for(var step:store.list("STEP",id+":",100)){if(!step.id().startsWith(id+":"))break;var d=step.data();if(d.has("taskId"))try{agents.get(Role.valueOf(d.path("role").asText())).cancel(d.path("taskId").asText());}catch(Exception ignored){}}
        return view(result);
    }
    /** Authorizes deterministic tool reads using the exact registered request and immutable network owner. */
    public void authorizeTool(Role caller,Request request){var w=owned(request.workflowId()).data();require(activeStates().contains(w.path("state").asText())&&w.path("revision").asInt()==request.planRevision(),"STALE_SCOPE","Inactive investigation");var step=store.get("STEP",request.workflowId()+":"+request.stepId()).data();require(step.path("role").asText().equals(caller.name())&&hash(step.path("request")).equals(hash(request)),"STEP_AUTHORITY","Tool calls require the registered delegation");}
    /** Reserves agent work only for a registered step and the currently active workflow revision. */
    public void reserve(Role caller,Request request,String callId,long tokens){
        network.accept(caller,request);var w=owned(request.workflowId()).data();
        require(activeStates().contains(w.path("state").asText())&&w.path("revision").asInt()==request.planRevision(),"STALE_SCOPE","Inactive workflow or revision");
        var step=store.get("STEP",request.workflowId()+":"+request.stepId()).data();
        require(step.path("role").asText().equals(caller.name())&&step.path("request").path("requestId").asText().equals(request.requestId()),"STEP_AUTHORITY","Unregistered agent delegation");
        require(tokens>0&&tokens<=250000&&callId.startsWith(request.requestId()+":"),"INVALID_RESERVATION","Invalid model reservation");
        store.reserve(request.workflowId(),"model:"+callId,0,1,tokens,new BigDecimal(System.getenv().getOrDefault("AI_MAX_USD_PER_TOKEN","0.00005")).max(new BigDecimal("0.00005")).multiply(BigDecimal.valueOf(tokens)));
    }
    /** Usage settlement authenticates the original role and step even if cancellation happened after dispatch. */
    public void settle(Role caller,Request request,String callId,long tokens){
        network.accept(caller,request);owned(request.workflowId());
        var step=store.get("STEP",request.workflowId()+":"+request.stepId()).data();
        require(step.path("role").asText().equals(caller.name())&&step.at("/request/requestId").asText().equals(request.requestId())&&callId.startsWith(request.requestId()+":"),"STEP_AUTHORITY","Unregistered usage settlement");
        store.settle(request.workflowId(),"model:"+callId,tokens);
    }
    /** Dispatches only this network's workflows; another network can progress while this worker is busy. */
    private void tick(){
        try{String after="";for(;;){var page=store.list("WORKFLOW",after,100);for(var w:page){
            if(closed)return;if(!owns(w.data()))continue;
            try{executeState(w.id(),w.data().path("state").asText());}
            catch(Yield ignored){}catch(Fault e){if(closed)return;fail(w.id(),e.code,e.getMessage());}
            catch(Exception e){if(closed)return;fail(w.id(),"WORKFLOW_FAILURE","Workflow stopped; completed evidence remains available");}
        }if(page.size()<100)break;after=page.get(page.size()-1).id();}}catch(Exception ignored){}
    }
    private void fail(String id,String code,String message){mutate(id,n->{n.put("resumeState",n.path("state").asText());n.put("state",code.equals("BUDGET_EXHAUSTED")||code.equals("DEADLINE")?"PAUSED_BUDGET":code.equals("NO_SUPPORTED_CANDIDATE")?"NO_SUPPORTED_CANDIDATE":"FAILED");n.set("error",obj("code",code,"message",message));});}
    void mutate(String id,Consumer<ObjectNode> change){
        for(int i=0;i<5;i++){var old=owned(id);if(old.data().path("state").asText().equals("CANCELED"))return;
            var n=(ObjectNode)old.data().deepCopy();change.accept(n);try{store.update("WORKFLOW",id,old.version(),n);return;}catch(Fault e){if(!e.code.equals("STALE_VERSION"))throw e;}}
        throw new Fault(409,"STATE_CONTENTION","Workflow state changed repeatedly");
    }
    JsonNode current(String id){var w=owned(id).data();if(closed||w.path("state").asText().equals("CANCELED"))throw new Fault(409,"CANCELED","Workflow canceled");return w;}
    /** Retries failed agent tasks with distinct, budgeted identities while replaying uncertain sends under the same identity. */
    Result agent(String id,Role role,String stepName,JsonNode payload){
        require(network.roles().contains(role),"NETWORK_SCOPE","Agent role is not available in this network");
        for(int attempt=0;attempt<3;attempt++){final int index=attempt;try{return dependency(id,()->agentAttempt(id,role,stepName+"-a"+index,payload));}
        catch(Fault e){if(attempt==2||!e.code.startsWith("AGENT_TASK_STATE_"))throw e;}}
        throw new Fault(503,"AGENT_FAILURE","Agent attempts exhausted");
    }
    /** Resolves a stage's revision internally once; a second unresolved decision becomes a final blocked result. */
    Result autonomousAgent(String id,Role role,String stepName,JsonNode payload){
        var result=agent(id,role,stepName,payload);
        if(result.decision().status().equals("REVISE")){
            var corrected=(ObjectNode)payload.deepCopy();
            corrected.set("internalReview",tree(result.decision()));
            corrected.put("resolution","Resolve issues using executionContext, evidence and answeredDecisions. Preserve hard constraints. Ask only a new material analyst decision; never resupply known facts, evidence, budgets or approvals.");
            result=agent(id,role,stepName+"-repair",corrected);
        }
        return result;
    }
    /** Replays completed steps and continues answered A2A tasks without re-running prior model turns or simulations. */
    private Result agentAttempt(String id,Role role,String stepName,JsonNode payload){
        var w=current(id);int revision=w.path("revision").asInt();String name="r"+revision+"-"+stepName,key=id+":"+name;var step=store.find("STEP",key);
        if(w.path("budgetRevision").asInt()>0&&(step.isEmpty()||!step.get().data().has("result"))){name=name+"-b"+w.path("budgetRevision").asInt();key=id+":"+name;step=store.find("STEP",key);}
        if(step.isEmpty()){
            var context=(ObjectNode)payload.deepCopy();context.put("network",network.name());context.set("executionContext",executionContext(w));context.set("answeredDecisions",w.path("answers"));
            // Budget extensions and failed-task retries retain the answer that caused this stage to resume.
            String logicalStep=key.replaceFirst("-a[0-9]+(?:-b[0-9]+)?$","");
            for(var answer:w.path("answers"))if(answer.path("stepKey").asText().replaceFirst("-a[0-9]+(?:-b[0-9]+)?$","").equals(logicalStep)){
                context.set("userAnswer",answer);
                context.set("previousObservations",store.get("STEP",answer.path("stepKey").asText()).data().at("/request/payload/previousObservations"));
            }
            var request=new Request("1.0.0",hash(List.of(id,name)).substring(0,48),id,name,revision,w.path("budget").path("deadline").asText(),context);
            step=Optional.of(store.create("STEP",key,obj("role",role.name(),"agentNetwork",network.name(),"request",request)));
        }
        var saved=step.get();var client=agents.get(role);
        for(;;){
            if(saved.data().has("result")){
                var result=convert(saved.data().path("result"),Result.class);
                if(result.decision().status().equals("FOLLOW_UP")){
                    require(network==Network.SIMULATION,"SUCCESSOR_REQUIRED","Create a separately authorized simulation from the publication");
                    require(saved.data().path("request").path("payload").has("userAnswer"),"FOLLOW_UP_AUTHORITY","A follow-up must follow an analyst answer");
                    mutate(id,n->{n.put("state","FOLLOW_UP_PENDING");n.set("followUp",obj("intent",result.decision().followUpIntent(),"reason",result.decision().summary(),"sourceStep",keyFor(result),"answer",savedAnswer(result)));});throw new Yield();
                }
                if(!result.decision().status().equals("NEEDS_INPUT"))return result;
                saved=continueQuestion(id,key,role,saved,result);
            }
            // Older investigations delegated to simulation agents. Preserve evidence and spent budget,
            // but give unfinished work a new, separately charged identity in the feedback agent group.
            if(network==Network.FEEDBACK&&!saved.data().path("agentNetwork").asText().equals(network.name())){
                var n=(ObjectNode)saved.data().deepCopy();var prior=convert(n.path("request"),Request.class);
                var context=(ObjectNode)prior.payload().deepCopy();context.put("network",network.name());
                n.set("request",tree(new Request(prior.schemaVersion(),hash(List.of(prior.requestId(),"feedback-network")).substring(0,48),prior.workflowId(),prior.stepId(),prior.planRevision(),prior.deadline(),context)));
                if(n.has("taskId"))n.set("legacyTaskId",n.path("taskId"));n.remove(List.of("taskId","resumePending"));n.put("agentNetwork",network.name());
                saved=store.update("STEP",key,saved.version(),n);
            }
            var request=convert(saved.data().path("request"),Request.class);
            if(!saved.data().has("taskId")||saved.data().path("resumePending").asBoolean()){
                var task=client.send(request,saved.data().has("taskId")?saved.data().path("taskId").asText():null);
                var n=(ObjectNode)saved.data().deepCopy();n.put("taskId",text(task,"id"));n.remove("resumePending");saved=store.update("STEP",key,saved.version(),n);
            }
            String taskId=saved.data().path("taskId").asText();
            current(id);if(!Instant.now().isBefore(Instant.parse(request.deadline())))throw new Fault(409,"DEADLINE","Workflow deadline reached");
            var task=client.get(taskId);String state=task.path("status").path("state").asText();
            if(Set.of("TASK_STATE_COMPLETED","TASK_STATE_INPUT_REQUIRED").contains(state)){
                var raw=task.path("artifacts").path(0).path("parts").path(0).path("data");var result=convert(raw,Result.class);
                require(result.workflowId().equals(id)&&result.requestId().equals(request.requestId())&&result.planRevision()==revision,"AGENT_CORRELATION","Agent returned an unrelated result");
                Contracts.decision(tree(result.decision()));var n=(ObjectNode)saved.data().deepCopy();n.set("result",raw);saved=store.update("STEP",key,saved.version(),n);
                String completedName=name;mutate(id,x->((ObjectNode)x.path("steps")).set(completedName,obj("role",role,"taskId",taskId,"result",result)));continue;
            }
            if(Set.of("TASK_STATE_FAILED","TASK_STATE_REJECTED","TASK_STATE_CANCELED").contains(state)){
                var failure=task.path("artifacts").path(0).path("parts").path(0).path("data");String code=failure.path("code").asText();
                if(Set.of("BUDGET_EXHAUSTED","DEADLINE").contains(code))throw new Fault(409,code,failure.path("message").asText());
                throw new Fault(409,"AGENT_"+state,"Agent "+role+" did not complete: "+failure.path("message").asText());
            }
            sleep(250);
        }
    }
    private static String keyFor(Result result){return result.workflowId()+":"+result.stepId();}
    private JsonNode savedAnswer(Result result){return store.get("STEP",keyFor(result)).data().at("/request/payload/userAnswer");}
    /** Exposes one focused question, then resumes that step with its answer and retained tool observations. */
    private Store.Saved continueQuestion(String id,String key,Role role,Store.Saved step,Result result){
        var w=current(id);String questionId=hash(List.of(result.requestId(),result.decision().question()));JsonNode answer=null;
        for(var a:w.path("answers"))if(a.path("questionId").asText().equals(questionId))answer=a;
        if(answer==null){
            require(w.path("answers").size()<12,"QUESTION_LIMIT","The bounded clarification limit was reached; completed results remain available");
            for(var a:w.path("answers"))require(!a.at("/question/decisionKey").asText().equals(result.decision().question().decisionKey()),"REPEATED_QUESTION","Agent repeated an answered decision; review the saved result");
            mutate(id,n->{if(!n.has("pendingQuestion"))n.set("pendingQuestion",obj("id",questionId,"agent",role,"stepKey",key,"taskId",step.data().path("taskId"),"resumeState",n.path("state"),"askedAt",Instant.now().toString(),"question",result.decision().question()));n.put("state","NEEDS_INPUT");});
            throw new Yield();
        }
        var old=convert(step.data().path("request"),Request.class);var payload=(ObjectNode)old.payload().deepCopy();
        var observations=new LinkedHashMap<String,JsonNode>();for(var observation:payload.path("previousObservations"))observations.put(hash(observation),observation);for(var observation:result.observations())observations.put(hash(observation),observation);
        payload.set("previousObservations",tree(observations.values()));
        payload.set("userAnswer",answer);payload.put("previousRequestId",old.requestId());payload.set("answeredDecisions",w.path("answers"));payload.set("executionContext",executionContext(w));
        var request=new Request(old.schemaVersion(),hash(List.of(old.requestId(),questionId)).substring(0,48),id,old.stepId(),old.planRevision(),w.at("/budget/deadline").asText(),payload);
        var data=(ObjectNode)step.data().deepCopy();data.withArray("turns").add(obj("request",old,"result",result));data.set("request",tree(request));data.remove("result");data.put("resumePending",true);
        return store.update("STEP",key,step.version(),data);
    }
    /** Returns a final, inspectable blocked outcome instead of converting agent reviews into analyst questions. */
    boolean stopIfUnresolved(String id,Result result){
        if(result.decision().status().equals("READY"))return false;
        mutate(id,n->{
            n.put("state","BLOCKED").put("completedAt",Instant.now().toString());n.remove("clarification");
            n.set("outcome",obj("status","BLOCKED","agent",result.kind(),"summary","The agent review could not resolve a constraint within its internal revision limit.",
                "detail",result.decision().summary(),"stepId",result.stepId()));
        });return true;
    }
    protected <T>T dependency(String id,java.util.function.Supplier<T> action){
        for(int attempt=0;attempt<3;attempt++){
            var w=current(id);if(!Instant.now().isBefore(Instant.parse(w.path("budget").path("deadline").asText())))throw new Fault(409,"DEADLINE","Workflow deadline reached");
            try{return action.get();}catch(Fault e){if(attempt==2||(e.status!=429&&e.status!=503))throw e;sleep(500L*(attempt+1));}
        }throw new IllegalStateException("Unreachable");
    }
    public static Set<String> strings(JsonNode n){var result=new TreeSet<String>();if(n.isArray())for(var s:n){require(s.isTextual(),"INVALID_STRING_LIST","String list required");result.add(s.asText());}return result;}
    protected static void sleep(long millis){try{Thread.sleep(millis);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new Fault(409,"INTERRUPTED","Worker interrupted");}}
    public void close(){closed=true;worker.shutdownNow();}
}
