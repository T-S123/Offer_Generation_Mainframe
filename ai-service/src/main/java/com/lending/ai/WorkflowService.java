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

/** Durable Plan-and-Execute coordinator: field approval, budgeted experiments, independent validation and reflection. */
public final class WorkflowService implements AutoCloseable {
    private final Store store;private final EngineGateway engine;private final Map<Role,A2AClient> agents;
    private final ParameterRegistry registry=new ParameterRegistry();private final Optimizer optimizer=new Optimizer(registry);
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private volatile boolean closed;
    private FeedbackService feedback;
    private java.util.function.BiConsumer<String,List<String>> evidenceValidator=(product,ids)->{};
    public void evidenceValidator(java.util.function.BiConsumer<String,List<String>> value){evidenceValidator=value;}
    public void validateEvidence(JsonNode workflow){var ids=new TreeSet<String>();for(var r:workflow.path("ranges"))r.path("evidenceIds").forEach(e->ids.add(e.asText()));evidenceValidator.accept(workflow.path("baseline").path("offer").path("product").asText(),List.copyOf(ids));}
    public void feedback(FeedbackService service){feedback=service;}
    EngineGateway engine(){return engine;}
    public WorkflowService(Store store,EngineGateway engine,Map<Role,A2AClient> agents){this.store=store;this.engine=engine;this.agents=Map.copyOf(agents);}
    public void start(){worker.scheduleWithFixedDelay(this::tick,0,1,TimeUnit.SECONDS);}
    public Store.Saved get(String id,String analyst){var w=store.get("WORKFLOW",id);if(!w.data().path("analyst").asText().equals(analyst))throw new Fault(404,"NOT_FOUND","Workflow not found");return w;}
    public List<Store.Saved> list(String analyst,String after,int limit){return store.list("WORKFLOW",after,limit).stream().filter(w->w.data().path("analyst").asText().equals(analyst)).map(w->new Store.Saved(w.id(),w.version(),obj("state",w.data().path("state"),"intent",w.data().path("intent"),"createdAt",w.data().path("createdAt")))).toList();}
    public JsonNode create(JsonNode body,String analyst){
        fields(body,"requestId","draftId","populationId","validationPopulationId","intent","lockedStages","budget","seed","predecessorPublicationId");
        String request=text(body,"requestId"),intent=text(body,"intent");require(request.matches("[A-Za-z0-9._:-]{1,80}")&&intent.length()<=6000,"REQUEST_LIMIT","Invalid request identity or intent");
        String id=hash(List.of(analyst,request));var prior=store.find("WORKFLOW",id);
        if(prior.isPresent()){require(prior.get().data().path("requestHash").asText().equals(hash(body)),"REQUEST_CONFLICT","Request identity changed");return view(prior.get());}
        String discovery=text(body,"populationId"),validation=text(body,"validationPopulationId");
        require(!discovery.equals(validation),"VALIDATION_LEAKAGE","Use a separate untouched validation population");
        var a=engine.population(discovery);var b=engine.population(validation);
        require(a.path("seed").asLong()!=b.path("seed").asLong(),"VALIDATION_LEAKAGE","Discovery and final validation must have different generation seeds");
        var budget=convert(body.path("budget"),Store.Budget.class);budget.validate();
        var draft=engine.baseline(text(body,"draftId"));var baseline=ParameterRegistry.configuration(draft);
        var locks=strings(body.path("lockedStages"));require(Set.of("offer","underwriting","marketing","bureau").containsAll(locks),"INVALID_LOCK","Unknown locked stage");
        var data=obj("id",id,"analyst",analyst,"requestHash",hash(body),"intent",intent,"baselineDraft",draft,"baseline",baseline,"baselineHash",hash(baseline),
            "populationId",discovery,"validationPopulationId",validation,"population",a,"validationPopulation",b,"budget",budget,"seed",body.path("seed").asLong(20260918),
            "lockedStages",locks,"revision",1,"state","PLANNING","createdAt",Instant.now().toString(),"messages",List.of(obj("role","analyst","text",intent)),
            "predecessorPublicationId",body.path("predecessorPublicationId").asText(""),"evaluations",List.of(),"candidates",List.of(),"steps",obj(),"limitations",List.of());
        String validationKey=hash(List.of(b.path("seed"),b.path("count"),b.path("generatorVersion")));
        String discoveryKey=hash(List.of(a.path("seed"),a.path("count"),a.path("generatorVersion")));
        require(store.find("VALIDATION_USE",discoveryKey).isEmpty(),"VALIDATION_LEAKAGE","A final-validation population cannot later become discovery data");
        var used=store.find("VALIDATION_USE",validationKey);
        require(used.isEmpty()||used.get().data().path("workflowId").asText().equals(id),"VALIDATION_LEAKAGE","Final validation was already reserved; generate a fresh population");
        if(used.isEmpty())store.create("VALIDATION_USE",validationKey,obj("workflowId",id,"populationId",validation,"reservedAt",Instant.now().toString()));
        store.budget(id,budget);
        return view(store.create("WORKFLOW",id,data));
    }
    /** Returns a bounded projection; full trial configurations and immutable reports are separately paginated. */
    public JsonNode view(Store.Saved saved){
        var data=(ObjectNode)saved.data().deepCopy();data.remove(List.of("explorationPlan","refinementPlan","refinementPlan0","refinementPlan1","validationPlan","candidates","baselineDraft"));
        var steps=object();saved.data().path("steps").fields().forEachRemaining(e->steps.set(e.getKey(),obj("role",e.getValue().path("role"),"taskId",e.getValue().path("taskId"),"resultRef",store.artifact((Object)e.getValue().path("result")))));data.set("steps",steps);
        var trials=MAPPER.createArrayNode();for(var e:saved.data().path("evaluations"))trials.add(obj("candidateId",e.path("candidateId"),"phase",e.path("phase"),"status",e.path("status"),"runId",e.path("runId"),"score",e.path("score"),"reportRef",e.path("reportRef")));data.set("evaluations",trials);
        return obj("id",saved.id(),"version",saved.version(),"workflow",data,"usage",store.budget(saved.id()));
    }
    public JsonNode evaluations(String id,String analyst,int offset,int limit){
        var rows=get(id,analyst).data().path("evaluations");require(offset>=0&&offset<=rows.size()&&limit>0&&limit<=20,"PAGE_LIMIT","Use a valid offset and limit 1-20");
        var items=MAPPER.createArrayNode();for(int i=offset;i<Math.min(offset+limit,rows.size());i++)items.add(rows.get(i));
        return obj("items",items,"offset",offset,"total",rows.size(),"nextOffset",offset+items.size()<rows.size()?offset+items.size():null);
    }
    /** Resolves only content explicitly referenced by this analyst's workflow, including delegated results. */
    public JsonNode artifact(String id,String analyst,String ref){
        var w=get(id,analyst).data();boolean found=references(w,ref);
        for(var step:w.path("steps"))if(("sha256:"+hash(step.path("result"))).equals(ref))found=true;
        if(!found)throw new Fault(404,"NOT_FOUND","Artifact not found in this workflow");return store.artifact(ref);
    }
    private static boolean references(JsonNode n,String ref){if(n.isTextual())return n.asText().equals(ref);for(var child:n)if(references(child,ref))return true;return false;}
    public JsonNode confirm(String id,String analyst,JsonNode body){
        fields(body,"expectedVersion","scopeHash");
        var w=get(id,analyst);require(w.version()==body.path("expectedVersion").asInt(),"STALE_VERSION","Reload the proposed ranges");
        require(w.data().path("state").asText().equals("AWAITING_SCOPE"),"WRONG_STATE","Workflow is not awaiting scope approval");
        require(w.data().path("scopeHash").asText().equals(text(body,"scopeHash")),"SCOPE_HASH","Approve the exact displayed ranges");
        var n=(ObjectNode)w.data().deepCopy();n.put("state","EXECUTING").put("approvedAt",Instant.now().toString()).put("approvedBy",analyst);
        return view(store.update("WORKFLOW",id,w.version(),n));
    }
    public JsonNode message(String id,String analyst,JsonNode body){
        fields(body,"expectedVersion","text");var w=get(id,analyst);require(w.version()==body.path("expectedVersion").asInt(),"STALE_VERSION","Reload conversation");
        require(w.data().path("evaluations").isEmpty()&&!w.data().has("approvedAt"),"SUCCESSOR_REQUIRED","An executed experiment is immutable; create a successor workflow to change scope");
        require(Set.of("NEEDS_INPUT","AWAITING_SCOPE","REVIEW_REQUIRED","FAILED").contains(w.data().path("state").asText()),"WRONG_STATE","Cancel an active experiment before revising its plan");
        String message=text(body,"text");require(message.length()<=6000,"MESSAGE_LIMIT","Message too long");var n=(ObjectNode)w.data().deepCopy();
        ((ArrayNode)n.path("messages")).add(obj("role","analyst","text",message));n.put("intent",n.path("intent").asText()+"\n"+message);
        require(n.path("intent").asText().length()<=12000,"CONVERSATION_LIMIT","Create a successor workflow for further changes");
        n.put("revision",n.path("revision").asInt()+1).put("state",n.path("kind").asText().equals("FEEDBACK")?"FEEDBACK_PENDING":"PLANNING");n.remove(List.of("scopeHash","ranges","allowedParameterIds","error","analysis","reflection"));
        return view(store.update("WORKFLOW",id,w.version(),n));
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
    /** Reserves agent work only for a registered step and the currently active workflow revision. */
    public void reserve(Role caller,Request request,String callId,long tokens){
        var w=store.get("WORKFLOW",request.workflowId()).data();
        require(Set.of("PLANNING","EXECUTING","FEEDBACK_PENDING").contains(w.path("state").asText())&&w.path("revision").asInt()==request.planRevision(),"STALE_SCOPE","Inactive workflow or revision");
        var step=store.get("STEP",request.workflowId()+":"+request.stepId()).data();
        require(step.path("role").asText().equals(caller.name())&&step.path("request").path("requestId").asText().equals(request.requestId()),"STEP_AUTHORITY","Unregistered agent delegation");
        require(tokens>0&&tokens<=250000&&callId.startsWith(request.requestId()+":"),"INVALID_RESERVATION","Invalid model reservation");
        store.reserve(request.workflowId(),"model:"+callId,0,1,tokens,new BigDecimal(System.getenv().getOrDefault("AI_MAX_USD_PER_TOKEN","0.00005")).max(new BigDecimal("0.00005")).multiply(BigDecimal.valueOf(tokens)));
    }
    /** Usage settlement authenticates the original role and step even if cancellation happened after dispatch. */
    public void settle(Role caller,Request request,String callId,long tokens){
        var step=store.get("STEP",request.workflowId()+":"+request.stepId()).data();
        require(step.path("role").asText().equals(caller.name())&&step.at("/request/requestId").asText().equals(request.requestId())&&callId.startsWith(request.requestId()+":"),"STEP_AUTHORITY","Unregistered usage settlement");
        store.settle(request.workflowId(),"model:"+callId,tokens);
    }
    private void tick(){
        try{String after="";for(;;){var page=store.list("WORKFLOW",after,100);for(var w:page){
            if(closed)return;
            try{switch(w.data().path("state").asText()){case "PLANNING"->prepare(w.id());case "EXECUTING"->execute(w.id());case "FEEDBACK_PENDING"->{if(feedback!=null)feedback.execute(w.id());}default->{}}}
            catch(Fault e){if(closed)return;fail(w.id(),e.code,e.getMessage());}catch(Exception e){if(closed)return;fail(w.id(),"WORKFLOW_FAILURE","Workflow stopped; completed engine receipts remain available");}
        }if(page.size()<100)break;after=page.get(page.size()-1).id();}}
        catch(Exception ignored){/* The next scheduler pass retries store reads; no external write is inferred. */}
    }
    private void fail(String id,String code,String message){mutate(id,n->{n.put("resumeState",n.path("state").asText());n.put("state",code.equals("BUDGET_EXHAUSTED")||code.equals("DEADLINE")?"PAUSED_BUDGET":"FAILED");n.set("error",obj("code",code,"message",message));});}
    void mutate(String id,Consumer<ObjectNode> change){
        for(int i=0;i<5;i++){var old=store.get("WORKFLOW",id);if(old.data().path("state").asText().equals("CANCELED"))return;
            var n=(ObjectNode)old.data().deepCopy();change.accept(n);try{store.update("WORKFLOW",id,old.version(),n);return;}catch(Fault e){if(!e.code.equals("STALE_VERSION"))throw e;}}
        throw new Fault(409,"STATE_CONTENTION","Workflow state changed repeatedly");
    }
    JsonNode current(String id){var w=store.get("WORKFLOW",id).data();if(closed||w.path("state").asText().equals("CANCELED"))throw new Fault(409,"CANCELED","Workflow canceled");return w;}
    /** Retries failed agent tasks with distinct, budgeted identities while replaying uncertain sends under the same identity. */
    Result agent(String id,Role role,String stepName,JsonNode payload){
        for(int attempt=0;attempt<3;attempt++){final int index=attempt;try{return dependency(id,()->agentAttempt(id,role,stepName+"-a"+index,payload));}
        catch(Fault e){if(attempt==2||!e.code.startsWith("AGENT_TASK_STATE_"))throw e;}}
        throw new Fault(503,"AGENT_FAILURE","Agent attempts exhausted");
    }
    private Result agentAttempt(String id,Role role,String stepName,JsonNode payload){
        var w=current(id);int revision=w.path("revision").asInt();String name="r"+revision+"-"+stepName,key=id+":"+name;var step=store.find("STEP",key);
        if(w.path("budgetRevision").asInt()>0&&(step.isEmpty()||!step.get().data().has("result"))){name=name+"-b"+w.path("budgetRevision").asInt();key=id+":"+name;step=store.find("STEP",key);}
        if(step.isEmpty()){
            var request=new Request("1.0.0",hash(List.of(id,name)).substring(0,48),id,name,revision,w.path("budget").path("deadline").asText(),payload);
            step=Optional.of(store.create("STEP",key,obj("role",role.name(),"request",request)));
        }
        var saved=step.get();if(saved.data().has("result"))return convert(saved.data().path("result"),Result.class);
        var request=convert(saved.data().path("request"),Request.class);var client=agents.get(role);
        if(!saved.data().has("taskId")){
            var task=client.send(request);String taskId=text(task,"id");var n=(ObjectNode)saved.data().deepCopy();n.put("taskId",taskId);
            saved=store.update("STEP",key,saved.version(),n);
        }
        String taskId=saved.data().path("taskId").asText();
        for(;;){
            current(id);if(!Instant.now().isBefore(Instant.parse(request.deadline())))throw new Fault(409,"DEADLINE","Workflow deadline reached");
            var task=client.get(taskId);String state=task.path("status").path("state").asText();
            if(Set.of("TASK_STATE_COMPLETED","TASK_STATE_INPUT_REQUIRED").contains(state)){
                var raw=task.path("artifacts").path(0).path("parts").path(0).path("data");var result=convert(raw,Result.class);
                require(result.workflowId().equals(id)&&result.requestId().equals(request.requestId())&&result.planRevision()==revision,"AGENT_CORRELATION","Agent returned an unrelated result");
                Contracts.decision(tree(result.decision()));var n=(ObjectNode)saved.data().deepCopy();n.set("result",raw);store.update("STEP",key,saved.version(),n);
                String completedName=name;mutate(id,x->((ObjectNode)x.path("steps")).set(completedName,obj("role",role,"taskId",taskId,"result",result)));return result;
            }
            if(Set.of("TASK_STATE_FAILED","TASK_STATE_REJECTED","TASK_STATE_CANCELED").contains(state)){
                var failure=task.path("artifacts").path(0).path("parts").path(0).path("data");String code=failure.path("code").asText();
                if(Set.of("BUDGET_EXHAUSTED","DEADLINE").contains(code))throw new Fault(409,code,failure.path("message").asText());
                throw new Fault(409,"AGENT_"+state,"Agent "+role+" did not complete: "+failure.path("message").asText());
            }
            sleep(250);
        }
    }
    private boolean needsInput(String id,Result result){
        if(result.decision().status().equals("READY"))return false;
        mutate(id,n->{n.put("state","NEEDS_INPUT");n.set("clarification",tree(result.decision().clarification()==null?result.decision().summary():result.decision().clarification()));});return true;
    }
    private void prepare(String id){
        var w=current(id);String product=w.path("baseline").path("offer").path("product").asText();
        var intent=agent(id,Role.ORCHESTRATOR,"interpret",obj("intent",w.path("intent"),"lockedStages",w.path("lockedStages"),"product",product,"parameters",registry.all()));
        if(needsInput(id,intent))return;
        var allowed=new TreeSet<>(intent.decision().allowedParameterIds());var locks=strings(w.path("lockedStages"));locks.addAll(intent.decision().lockedStages());
        require(!allowed.isEmpty(),"EMPTY_SCOPE","No parameters selected");
        for(String p:allowed)require(!locks.contains(registry.get(p).stage()),"SCOPE_VIOLATION","Requested parameter is in a locked stage");
        var research=agent(id,Role.RESEARCH,"research",obj("intent",w.path("intent"),"product",product,"allowedParameterIds",allowed,"baseline",w.path("baseline"),"lockedStages",locks));
        if(needsInput(id,research))return;
        var evidence=new HashSet<String>();
        for(var observation:research.observations())for(var item:observation.path("result").path("evidence"))evidence.add(item.path("evidenceId").asText());
        var ranges=research.decision().ranges();
        require(ranges.size()==allowed.size(),"MISSING_RANGES","Research must support every selected parameter");
        var seen=new HashSet<String>();
        for(var r:ranges){
            require(allowed.contains(r.parameterId())&&seen.add(r.parameterId()),"SCOPE_VIOLATION","Research changed parameter scope");
            require(r.evidenceIds()!=null&&!r.evidenceIds().isEmpty()&&evidence.containsAll(r.evidenceIds()),"EVIDENCE_REQUIRED","Every range needs retrieved approved policy evidence");
            registry.levels(r,product,w.path("baseline").at(r.parameterId()),10);
        }
        var designer=agent(id,Role.DESIGNER,"design",obj("baseline",w.path("baseline"),"product",product,"allowedParameterIds",allowed,"lockedStages",locks,"ranges",ranges,"budget",w.path("budget"),"evidence",research));
        if(needsInput(id,designer))return;
        var reflection=agent(id,Role.REFLECTION,"plan-review",obj("reviewType","PLAN","baseline",w.path("baseline"),"allowedParameterIds",allowed,"lockedStages",locks,"ranges",ranges,"research",research,"designer",designer));
        if(needsInput(id,reflection))return;
        var scope=obj("baselineHash",w.path("baselineHash"),"allowedParameterIds",allowed,"lockedStages",locks,"ranges",ranges,"budget",w.path("budget"),"revision",w.path("revision"));
        mutate(id,n->{n.set("allowedParameterIds",tree(allowed));n.set("lockedStages",tree(locks));n.set("ranges",tree(ranges));n.set("scope",scope);n.put("scopeHash",hash(scope)).put("state","AWAITING_SCOPE");n.remove("clarification");});
    }
    private void execute(String id){
        var w=current(id);var baseline=(ObjectNode)w.path("baseline");var allowed=strings(w.path("allowedParameterIds"));var locks=strings(w.path("lockedStages"));
        var ranges=new ArrayList<ParameterRegistry.Range>();w.path("ranges").forEach(n->ranges.add(convert(n,ParameterRegistry.Range.class)));
        var budget=convert(w.path("budget"),Store.Budget.class);long seed=w.path("seed").asLong();
        require(hash(w.path("scope")).equals(w.path("scopeHash").asText()),"SCOPE_HASH","Approved scope was altered");
        var coordinator=agent(id,Role.COORDINATOR,"admission-review",obj("scope",w.path("scope"),"population",w.path("population"),"validationPopulation",w.path("validationPopulation")));
        if(needsInput(id,coordinator))return;
        evaluate(id,new Optimizer.Candidate(hash(baseline),"BASELINE",baseline,Set.of()),false);
        var candidates=plan(id,"explorationPlan",()->optimizer.explore(baseline,ranges,allowed,locks,budget.exploration(),seed));
        for(var c:candidates)evaluate(id,c,false);
        for(int round=0;round<2;round++){
            var scores=scores(current(id),false);var ranking=Optimizer.rank(scores,budget.minimumEligible(),budget.eligibilityFloor(),budget.acceptanceFloor());
            var centers=new ArrayList<ObjectNode>();
            for(var score:ranking.frontier().stream().limit(8).toList())for(var c:current(id).path("candidates"))if(c.path("id").asText().equals(score.candidateId()))centers.add((ObjectNode)c.path("configuration"));
            if(centers.isEmpty())centers.add(baseline);
            Set<String> tested=new HashSet<>();current(id).path("candidates").forEach(c->tested.add(c.path("id").asText()));
            int allocated=round==0?budget.refinement()/2:budget.refinement()-budget.refinement()/2;long roundSeed=seed+round+1;
            var refine=plan(id,"refinementPlan"+round,()->optimizer.refine(baseline,ranges,allowed,locks,centers,tested,allocated,roundSeed));
            for(var c:refine)evaluate(id,c,false);
        }
        w=current(id);
        if(!w.has("shortlist")){
            var discovered=Optimizer.rank(scores(w,false),budget.minimumEligible(),budget.eligibilityFloor(),budget.acceptanceFloor());var shortlist=new LinkedHashSet<String>();
            if(discovered.acceptance()!=null)shortlist.add(discovered.acceptance().candidateId());if(discovered.eligibility()!=null)shortlist.add(discovered.eligibility().candidateId());
            for(var s:discovered.frontier())if(shortlist.size()<budget.validation())shortlist.add(s.candidateId());
            require(!shortlist.isEmpty(),"NO_SUPPORTED_CANDIDATE","No tested configuration meets support/floor constraints");
            mutate(id,n->{n.set("shortlist",tree(shortlist));n.set("discoveryWinners",tree(discovered));});
        }
        w=current(id);
        for(var c:w.path("candidates"))if(strings(w.path("shortlist")).contains(c.path("id").asText()))evaluate(id,convert(c,Optimizer.Candidate.class),true);
        var finalScores=scores(current(id),true);var winners=Optimizer.rank(finalScores,budget.minimumEligible(),budget.eligibilityFloor(),budget.acceptanceFloor());
        mutate(id,n->n.set("winners",tree(winners)));
        var analysis=agent(id,Role.ANALYZER,"results",obj("analysisMode","SIMULATION","winners",agentWinners(winners),"discoveryScores",agentScores(scores(current(id),false)),"validationScores",agentScores(scores(current(id),true)),"winnerComparisons",winnerComparisons(current(id),winners),"predictionKind","SIMULATED_UTILITY","predictionScope","CATALOG_TERMS_ONLY","sourceMode","DEMO","acceptanceDenominator","ELIGIBLE_CUSTOMERS","scope",current(id).path("scope")));
        var reflection=agent(id,Role.REFLECTION,"results-review",obj("reviewType","RESULTS","analysis",analysis,"computedWinners",agentWinners(winners),"scope",current(id).path("scope")));
        if(reflection.decision().status().equals("REVISE")){
            analysis=agent(id,Role.ANALYZER,"results-revision",obj("analysisMode","SIMULATION","critique",reflection,"original",analysis,"computedWinners",agentWinners(winners),"validationScores",agentScores(finalScores)));
            reflection=agent(id,Role.REFLECTION,"results-revision-review",obj("reviewType","RESULTS","analysis",analysis,"computedWinners",agentWinners(winners),"scope",current(id).path("scope")));
        }
        var finalAnalysis=analysis;var finalReflection=reflection;
        mutate(id,n->{n.set("analysis",tree(finalAnalysis));n.set("reflection",tree(finalReflection));n.put("state",finalAnalysis.decision().status().equals("READY")&&finalReflection.decision().status().equals("READY")?"COMPLETED":"REVIEW_REQUIRED");n.put("completedAt",Instant.now().toString());});
    }
    private static JsonNode agentScores(List<Optimizer.Score> scores){return obj("total",scores.size(),"displayed",scores.stream().limit(100).toList(),"truncated",scores.size()>100);}
    private static JsonNode agentWinners(Optimizer.Winners w){return obj("acceptance",w.acceptance(),"eligibility",w.eligibility(),"frontier",agentScores(w.frontier()));}
    /** Gives models paired baseline/candidate summaries for both final winners; all full reports remain reviewable. */
    private JsonNode winnerComparisons(JsonNode workflow,Optimizer.Winners winners){
        var selected=new HashSet<String>();if(winners.acceptance()!=null)selected.add(winners.acceptance().runId());if(winners.eligibility()!=null)selected.add(winners.eligibility().runId());
        var rows=new ArrayList<JsonNode>();for(var evaluation:workflow.path("evaluations"))if(selected.contains(evaluation.path("runId").asText())){
            var report=store.artifact(evaluation.path("reportRef").asText());
            rows.add(obj("runId",evaluation.path("runId"),"candidateId",evaluation.path("candidateId"),"configuration",evaluation.path("configuration"),"overall",report.path("overall"),"uncertainty","Synthetic utility sensitivity only; no real acceptance confidence interval."));
        }return tree(rows);
    }
    private void evaluate(String id,Optimizer.Candidate candidate,boolean validation){
        var w=current(id);validateEvidence(w);String phase=validation?"VALIDATION":candidate.phase();String evaluationId=id+":"+candidate.id().substring(0,32)+":"+phase;
        var prior=store.find("EVALUATION",evaluationId);
        if(prior.isPresent()&&Set.of("COMPLETED","FAILED").contains(prior.get().data().path("status").asText())){project(id,candidate,prior.get().data());return;}
        var base=(ObjectNode)w.path("baseline");var allowed=strings(w.path("allowedParameterIds"));var locks=strings(w.path("lockedStages"));
        registry.validatePatch(base,candidate.configuration(),allowed,locks);
        String population=w.path(validation?"validationPopulationId":"populationId").asText();
        if(prior.isEmpty()){
            store.reserve(id,"evaluation:"+candidate.id().substring(0,32)+":"+phase,1,0,0,BigDecimal.ZERO);
            prior=Optional.of(store.create("EVALUATION",evaluationId,obj("candidateId",candidate.id(),"phase",phase,"status","ADMITTED","configuration",candidate.configuration())));
        }
        var saved=prior.get();var data=(ObjectNode)saved.data().deepCopy();
        if(!data.has("draft")){
            var draft=dependency(id,()->engine.candidate(id,w.path("baselineDraft"),candidate.configuration(),candidate.id()));data.set("draft",draft);
            saved=store.update("EVALUATION",evaluationId,saved.version(),data);
        }
        if(!data.has("runId")){
            var run=dependency(id,()->engine.submit(evaluationId,data.path("draft"),population,w.path("seed").asLong()));data.put("runId",text(run,"id"));
            saved=store.update("EVALUATION",evaluationId,saved.version(),data);
        }
        mutate(id,n->{var rows=(ArrayNode)n.path("candidates");boolean exists=false;for(var c:rows)if(c.path("id").asText().equals(candidate.id()))exists=true;if(!exists)rows.add(tree(candidate));n.put("activeRunId",data.path("runId").asText());});
        JsonNode run;
        for(;;){current(id);if(!Instant.now().isBefore(Instant.parse(w.path("budget").path("deadline").asText())))throw new Fault(409,"DEADLINE","Simulation budget deadline reached");
            run=dependency(id,()->engine.run(data.path("runId").asText()));String state=run.path("status").asText();if(Set.of("COMPLETED","FAILED").contains(state))break;sleep(300);}
        data.put("status",run.path("status").asText());data.put("runId",run.path("id").asText());
        if(run.path("status").asText().equals("COMPLETED")){
            var overall=run.path("report").path("overall");var metrics=overall.path("candidate");
            int assessed=overall.path("population").asInt(),eligible=metrics.path("eligible").asInt();double expected=metrics.path("simulatedExpectedAcceptances").asDouble();
            require(assessed>0&&eligible>=0&&eligible<=assessed&&Double.isFinite(expected)&&expected>=0&&expected<=eligible+1e-8,"INVALID_METRICS","Engine returned invalid metric counts");
            var score=new Optimizer.Score(candidate.id(),run.path("id").asText(),assessed,eligible,eligible==0?null:100*expected/eligible,100d*eligible/assessed,expected,phase);
            data.set("score",tree(score));data.put("reportRef",store.artifact((Object)run.path("report")));
            data.set("prediction",obj("predictionKind","SIMULATED_UTILITY","scope","CATALOG_TERMS_ONLY","sourceMode","DEMO","metric","SELECTIONS_PER_ELIGIBLE","estimatePct",score.acceptancePct(),"populationId",population,"runId",score.runId(),"generatedAt",Instant.now().toString()));
        }else data.set("error",run.path("error"));
        store.update("EVALUATION",evaluationId,saved.version(),data);
        project(id,candidate,data);
    }
    /** Persists each phase before running it so restarts cannot choose new centers from refinement results. */
    private List<Optimizer.Candidate> plan(String id,String field,java.util.function.Supplier<List<Optimizer.Candidate>> supplier){
        var w=current(id);
        if(!w.has(field)){var generated=supplier.get();mutate(id,n->{if(!n.has(field))n.set(field,tree(generated));});}
        var result=new ArrayList<Optimizer.Candidate>();current(id).path(field).forEach(c->result.add(convert(c,Optimizer.Candidate.class)));return result;
    }
    /** Reconciles an evaluation committed before an interruption with its workflow projection. */
    private void project(String id,Optimizer.Candidate candidate,JsonNode data){
        mutate(id,n->{
            var candidates=(ArrayNode)n.path("candidates");boolean found=false;
            for(var c:candidates)if(c.path("id").asText().equals(candidate.id()))found=true;
            if(!found)candidates.add(tree(candidate));
            var rows=(ArrayNode)n.path("evaluations");found=false;
            for(var e:rows)if(e.path("runId").asText().equals(data.path("runId").asText()))found=true;
            if(!found)rows.add(data);n.remove("activeRunId");
        });
    }
    private <T>T dependency(String id,java.util.function.Supplier<T> action){
        for(int attempt=0;attempt<3;attempt++){
            var w=current(id);if(!Instant.now().isBefore(Instant.parse(w.path("budget").path("deadline").asText())))throw new Fault(409,"DEADLINE","Workflow deadline reached");
            try{return action.get();}catch(Fault e){if(attempt==2||(e.status!=429&&e.status!=503))throw e;sleep(500L*(attempt+1));}
        }throw new IllegalStateException("Unreachable");
    }
    static List<Optimizer.Score> scores(JsonNode w,boolean finalOnly){var result=new ArrayList<Optimizer.Score>();for(var e:w.path("evaluations"))if(e.has("score")&&e.path("phase").asText().equals("VALIDATION")==finalOnly)result.add(convert(e.path("score"),Optimizer.Score.class));return result;}
    public static Set<String> strings(JsonNode n){var result=new TreeSet<String>();if(n.isArray())for(var s:n){require(s.isTextual(),"INVALID_STRING_LIST","String list required");result.add(s.asText());}return result;}
    private static void sleep(long millis){try{Thread.sleep(millis);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new Fault(409,"INTERRUPTED","Worker interrupted");}}
    public void close(){closed=true;worker.shutdownNow();}
}
