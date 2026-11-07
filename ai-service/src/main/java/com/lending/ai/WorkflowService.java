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

/** Simulation-only offer design, bounded trials, held-out validation and reviewed publication evidence. */
public final class WorkflowService extends WorkflowRuntime {
    private final EngineGateway engine;
    private final ParameterRegistry registry=new ParameterRegistry();private final Optimizer optimizer=new Optimizer(registry);
    private java.util.function.BiConsumer<String,List<String>> evidenceValidator=(product,ids)->{};
    public void evidenceValidator(java.util.function.BiConsumer<String,List<String>> value){evidenceValidator=value;}
    public void validateEvidence(JsonNode workflow){var ids=new TreeSet<String>();for(var r:workflow.path("ranges"))r.path("evidenceIds").forEach(e->ids.add(e.asText()));evidenceValidator.accept(workflow.path("baseline").path("offer").path("product").asText(),List.copyOf(ids));}
    EngineGateway engine(){return engine;}
    public WorkflowService(Store store,EngineGateway engine,Map<Role,A2AClient> agents){super(store,agents,Network.SIMULATION);this.engine=engine;}
    protected String initialState(){return "PLANNING";}
    protected Set<String> activeStates(){return Set.of("PLANNING","EXECUTING");}
    protected JsonNode executionContext(JsonNode workflow){return AutonomousPolicy.context(workflow,store.budget(workflow.path("id").asText()));}
    protected void executeState(String id,String state){switch(state){case "PLANNING"->prepare(id);case "EXECUTING"->execute(id);case "FOLLOW_UP_PENDING"->followUp(id);default->{}}}
    public JsonNode create(JsonNode body,String analyst){
        fields(body,"requestId","draftId","populationId","validationPopulationId","intent","lockedStages","budget","seed","predecessorPublicationId","autoExecute");
        require(!body.has("autoExecute")||body.path("autoExecute").isBoolean(),"INVALID_EXECUTION_MODE","autoExecute must be boolean");
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
        var data=obj("id",id,"network","SIMULATION","kind","SIMULATION","analyst",analyst,"requestHash",hash(body),"intent",intent,"baselineDraft",draft,"baseline",baseline,"baselineHash",hash(baseline),
            "populationId",discovery,"validationPopulationId",validation,"population",a,"validationPopulation",b,"budget",budget,"seed",body.path("seed").asLong(20260918),
            "lockedStages",locks,"autoExecute",body.path("autoExecute").asBoolean(true),"revision",1,"state","PLANNING","createdAt",Instant.now().toString(),"messages",List.of(obj("role","analyst","text",intent)),"answers",List.of(),"experiments",List.of(),
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
    public JsonNode confirm(String id,String analyst,JsonNode body){
        fields(body,"expectedVersion","scopeHash");
        var w=get(id,analyst);require(w.version()==body.path("expectedVersion").asInt(),"STALE_VERSION","Reload the proposed ranges");
        require(w.data().path("state").asText().equals("AWAITING_SCOPE"),"WRONG_STATE","Workflow is not awaiting scope approval");
        require(w.data().path("scopeHash").asText().equals(text(body,"scopeHash")),"SCOPE_HASH","Approve the exact displayed ranges");
        var n=(ObjectNode)w.data().deepCopy();n.put("state","EXECUTING").put("approvedAt",Instant.now().toString()).put("approvedBy",analyst);
        return view(store.update("WORKFLOW",id,w.version(),n));
    }
    /** Archives a completed or partial experiment and rotates both populations before a budget-sharing follow-up. */
    private void followUp(String id){
        var w=current(id);
        var original=convert(w.path("budget"),Store.Budget.class);int remaining=original.maxEvaluations()-store.budget(id).path("evaluations").asInt();
        require(remaining>=4,"BUDGET_EXHAUSTED","A follow-up needs at least four remaining trials; prior results are saved");
        int validation=Math.min(original.validation(),Math.max(2,remaining/5));int refinement=Math.min(original.refinement(),Math.max(0,(remaining-validation-1)/3));int exploration=Math.min(original.exploration(),remaining-validation-refinement-1);
        int revision=w.path("revision").asInt()+1;require(revision<=20,"EXPERIMENT_LIMIT","Follow-up experiment limit reached");
        // The identity and seed are deterministic across uncertain engine responses; each population has a different generation seed.
        long seed=Long.parseUnsignedLong(hash(List.of(id,revision)).substring(0,12),16);
        var discovery=dependency(id,()->engine.generatePopulation(id+":r"+revision+":discovery",w.path("population").path("count").asInt(),seed));
        var validationPopulation=dependency(id,()->engine.generatePopulation(id+":r"+revision+":validation",w.path("validationPopulation").path("count").asInt(),seed+1));
        String validationKey=hash(List.of(validationPopulation.path("seed"),validationPopulation.path("count"),validationPopulation.path("generatorVersion")));
        var used=store.find("VALIDATION_USE",validationKey);require(used.isEmpty()||used.get().data().path("workflowId").asText().equals(id),"VALIDATION_LEAKAGE","Generated validation population is already reserved");
        if(used.isEmpty())store.create("VALIDATION_USE",validationKey,obj("workflowId",id,"populationId",validationPopulation.path("id"),"reservedAt",Instant.now().toString()));
        var archive=(ObjectNode)w.deepCopy();archive.remove(List.of("experiments","answers","messages","baselineDraft"));String ref=store.artifact((Object)archive);
        var phases=tree(new Store.Budget(original.maxEvaluations(),original.maxModelCalls(),original.maxTokens(),original.maxCostUsd(),original.deadline(),exploration,refinement,validation,original.minimumEligible(),original.eligibilityFloor(),original.acceptanceFloor()));
        mutate(id,n->{
            n.withArray("experiments").add(obj("revision",n.path("revision"),"resultRef",ref,"trialCount",n.path("evaluations").size(),"winners",n.path("winners"),"reason",n.path("followUp").path("reason")));
            n.put("intent",n.path("intent").asText()+"\nFollow-up requested by analyst: "+n.at("/followUp/answer/text").asText()+"\nRevised experiment: "+n.at("/followUp/intent").asText());
            n.put("revision",revision).put("state","PLANNING").put("autoExecute",true).put("populationId",discovery.path("id").asText()).put("validationPopulationId",validationPopulation.path("id").asText());
            n.set("population",discovery);n.set("validationPopulation",validationPopulation);n.set("phaseBudget",phases);
            n.set("evaluations",tree(List.of()));n.set("candidates",tree(List.of()));
            n.remove(List.of("scope","scopeHash","allowedParameterIds","ranges","explorationPlan","refinementPlan0","refinementPlan1","shortlist","discoveryWinners","winners","analysis","reflection","completedAt","approvedAt","error","outcome","followUp"));
            n.put("lastActivity","Linked follow-up started within the remaining budget; prior results are retained.");
        });
    }
    private void prepare(String id){
        var w=current(id);String product=w.path("baseline").path("offer").path("product").asText();
        var intent=autonomousAgent(id,Role.ORCHESTRATOR,"interpret",obj("stage","SCOPE_INTERPRETATION","intent",w.path("intent"),"conversation",w.path("messages"),"baseline",w.path("baseline"),"lockedStages",w.path("lockedStages"),"product",product,"parameters",registry.all(),"nextStage","Research retrieves approved policy automatically. Use executionContext for defaults. Ask only material decisions; never request policy text or reapproval."));
        if(stopIfUnresolved(id,intent))return;
        var allowed=new TreeSet<>(intent.decision().allowedParameterIds());var locks=strings(w.path("lockedStages"));locks.addAll(intent.decision().lockedStages());
        require(!allowed.isEmpty(),"EMPTY_SCOPE","No parameters selected");
        for(String p:allowed)require(!locks.contains(registry.get(p).stage()),"SCOPE_VIOLATION","Requested parameter is in a locked stage");
        var research=autonomousAgent(id,Role.RESEARCH,"research",obj("intent",w.path("intent"),"product",product,"allowedParameterIds",allowed,"baseline",w.path("baseline"),"lockedStages",locks));
        if(stopIfUnresolved(id,research))return;
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
        var designer=autonomousAgent(id,Role.DESIGNER,"design",obj("baseline",w.path("baseline"),"product",product,"allowedParameterIds",allowed,"lockedStages",locks,"ranges",ranges,"budget",w.path("budget"),"evidence",research));
        if(stopIfUnresolved(id,designer))return;
        var reflection=agent(id,Role.REFLECTION,"plan-review",obj("reviewType","PLAN","baseline",w.path("baseline"),"allowedParameterIds",allowed,"lockedStages",locks,"ranges",ranges,"research",research,"designer",designer));
        for(int repair=1;repair<=2&&reflection.decision().status().equals("REVISE");repair++){
            designer=autonomousAgent(id,Role.DESIGNER,"design-repair-"+repair,obj("baseline",w.path("baseline"),"product",product,"allowedParameterIds",allowed,"lockedStages",locks,"ranges",ranges,"budget",w.path("budget"),"evidence",research,"internalReview",reflection));
            if(stopIfUnresolved(id,designer))return;
            reflection=agent(id,Role.REFLECTION,"plan-review-repair-"+repair,obj("reviewType","PLAN","baseline",w.path("baseline"),"allowedParameterIds",allowed,"lockedStages",locks,"ranges",ranges,"research",research,"designer",designer));
        }
        if(stopIfUnresolved(id,reflection))return;
        var scope=obj("baselineHash",w.path("baselineHash"),"allowedParameterIds",allowed,"lockedStages",locks,"ranges",ranges,"budget",w.path("budget"),"revision",w.path("revision"));
        mutate(id,n->{n.set("allowedParameterIds",tree(allowed));n.set("lockedStages",tree(locks));n.set("ranges",tree(ranges));n.set("scope",scope);n.put("scopeHash",hash(scope)).put("state",n.path("autoExecute").asBoolean()?"EXECUTING":"AWAITING_SCOPE");n.remove("clarification");
            if(n.path("autoExecute").asBoolean())n.put("approvedAt",Instant.now().toString()).put("approvedBy",n.path("analyst").asText()).put("authorization","SUBMITTED_REQUEST_AND_BUDGET");});
    }
    private void execute(String id){
        var w=current(id);var baseline=(ObjectNode)w.path("baseline");var allowed=strings(w.path("allowedParameterIds"));var locks=strings(w.path("lockedStages"));
        var ranges=new ArrayList<ParameterRegistry.Range>();w.path("ranges").forEach(n->ranges.add(convert(n,ParameterRegistry.Range.class)));
        var budget=convert(w.has("phaseBudget")?w.path("phaseBudget"):w.path("budget"),Store.Budget.class);long seed=w.path("seed").asLong();
        require(hash(w.path("scope")).equals(w.path("scopeHash").asText()),"SCOPE_HASH","Approved scope was altered");
        var coordinator=autonomousAgent(id,Role.COORDINATOR,"admission-review",obj("scope",w.path("scope"),"population",w.path("population"),"validationPopulation",w.path("validationPopulation")));
        if(stopIfUnresolved(id,coordinator))return;
        evaluate(id,new Optimizer.Candidate(hash(baseline),"BASELINE",baseline,Set.of()),false);
        var candidates=plan(id,"explorationPlan",()->optimizer.explore(baseline,ranges,allowed,locks,budget.exploration(),seed));
        for(var c:candidates)evaluate(id,c,false);
        var interim=autonomousAgent(id,Role.ANALYZER,"discovery-review",obj("analysisMode","INTERIM_SIMULATION","intent",w.path("intent"),"discoveryScores",agentScores(scores(current(id),false)),"scope",current(id).path("scope"),
            "nextStep","Refine the current supported range. Ask a material tradeoff or next-experiment question only if the analyst must decide; otherwise READY. Do not tune on final validation."));
        if(stopIfUnresolved(id,interim))return;
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
        var reflection=agent(id,Role.REFLECTION,"results-review",obj("reviewType","RESULTS","winnerComparisons",winnerComparisons(current(id),winners),"analysis",analysis,"computedWinners",agentWinners(winners),"scope",current(id).path("scope")));
        if(reflection.decision().status().equals("REVISE")){
            analysis=agent(id,Role.ANALYZER,"results-revision",obj("analysisMode","SIMULATION","winnerComparisons",winnerComparisons(current(id),winners),"critique",reflection,"original",analysis,"computedWinners",agentWinners(winners),"validationScores",agentScores(finalScores)));
            reflection=agent(id,Role.REFLECTION,"results-revision-review",obj("reviewType","RESULTS","winnerComparisons",winnerComparisons(current(id),winners),"analysis",analysis,"computedWinners",agentWinners(winners),"scope",current(id).path("scope")));
        }
        var finalAnalysis=analysis;var finalReflection=reflection;
        mutate(id,n->{n.set("analysis",tree(finalAnalysis));n.set("reflection",tree(finalReflection));n.put("state",finalAnalysis.decision().status().equals("READY")&&finalReflection.decision().status().equals("READY")?"COMPLETED":"REVIEW_REQUIRED");n.put("completedAt",Instant.now().toString());});
    }
    private static JsonNode agentScores(List<Optimizer.Score> scores){return obj("total",scores.size(),"displayed",scores.stream().limit(100).toList(),"truncated",scores.size()>100);}
    private static JsonNode agentWinners(Optimizer.Winners w){return obj("acceptance",w.acceptance(),"eligibility",w.eligibility(),"frontier",agentScores(w.frontier()));}
    /** Labels paired catalog/candidate summaries separately from the frozen starting draft so models cannot infer AI scope changes from catalog deltas. */
    private JsonNode winnerComparisons(JsonNode workflow,Optimizer.Winners winners){
        var selected=new HashSet<String>();if(winners.acceptance()!=null)selected.add(winners.acceptance().runId());if(winners.eligibility()!=null)selected.add(winners.eligibility().runId());
        var rows=new ArrayList<JsonNode>();for(var evaluation:workflow.path("evaluations"))if(selected.contains(evaluation.path("runId").asText())){
            var report=store.artifact(evaluation.path("reportRef").asText());
            rows.add(obj("runId",evaluation.path("runId"),"candidateId",evaluation.path("candidateId"),"configuration",evaluation.path("configuration"),"overall",report.path("overall"),
                "comparisonBaseline","ORIGINAL_CATALOG_OFFER","catalogBaseline",workflow.path("baselineDraft").path("baseline"),"startingDraftConfiguration",workflow.path("baseline"),
                "comparisonMeaning","overall.baseline and paired deltas compare the original catalog offer against this candidate on the same population. The analyst edited the starting draft before AI. Catalog differences in locked fields are not AI scope changes. Judge scope against startingDraftConfiguration; compare AI trials using their candidate metrics on the same population.",
                "uncertainty","Synthetic utility sensitivity only; no real acceptance confidence interval."));
        }return tree(rows);
    }
    private void evaluate(String id,Optimizer.Candidate candidate,boolean validation){
        var w=current(id);validateEvidence(w);String phase=validation?"VALIDATION":candidate.phase();String experiment=w.path("revision").asInt()==1?"":"r"+w.path("revision").asInt()+":";String evaluationId=id+":"+experiment+candidate.id().substring(0,32)+":"+phase;
        var prior=store.find("EVALUATION",evaluationId);
        if(prior.isPresent()&&Set.of("COMPLETED","FAILED").contains(prior.get().data().path("status").asText())){project(id,candidate,prior.get().data());return;}
        var base=(ObjectNode)w.path("baseline");var allowed=strings(w.path("allowedParameterIds"));var locks=strings(w.path("lockedStages"));
        registry.validatePatch(base,candidate.configuration(),allowed,locks);
        String population=w.path(validation?"validationPopulationId":"populationId").asText();
        if(prior.isEmpty()){
            store.reserve(id,"evaluation:"+experiment+candidate.id().substring(0,32)+":"+phase,1,0,0,BigDecimal.ZERO);
            prior=Optional.of(store.create("EVALUATION",evaluationId,obj("candidateId",candidate.id(),"phase",phase,"status","ADMITTED","configuration",candidate.configuration())));
        }
        var saved=prior.get();var data=(ObjectNode)saved.data().deepCopy();
        if(!data.has("draft")){
            var draft=dependency(id,()->engine.candidate(id+(experiment.isEmpty()?"":":"+experiment.substring(0,experiment.length()-1)),w.path("baselineDraft"),candidate.configuration(),candidate.id()));data.set("draft",draft);
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
            data.set("prediction",obj("predictionKind","SIMULATED_UTILITY","scope","CATALOG_TERMS_ONLY","sourceMode","DEMO","metric","SELECTIONS_PER_ELIGIBLE","estimatePct",score.acceptancePct(),"eligibilityPct",score.eligibilityPct(),"assessedCount",score.assessed(),"eligibleCount",score.eligible(),"populationId",population,"runId",score.runId(),"generatedAt",Instant.now().toString()));
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
    static List<Optimizer.Score> scores(JsonNode w,boolean finalOnly){var result=new ArrayList<Optimizer.Score>();for(var e:w.path("evaluations"))if(e.has("score")&&e.path("phase").asText().equals("VALIDATION")==finalOnly)result.add(convert(e.path("score"),Optimizer.Score.class));return result;}
}
