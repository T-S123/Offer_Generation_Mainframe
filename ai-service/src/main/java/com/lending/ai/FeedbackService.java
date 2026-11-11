package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import static com.lending.ai.Json.*;
import static com.lending.ai.Contracts.*;

/** Network 2 owns scoped investigations and Business briefings over frozen recorded evidence. */
public final class FeedbackService {
    private final Store store;private final InvestigationWorkflowService workflows;private final WorkflowService simulations;private final EngineGateway engine;
    private final PerformanceBriefing briefing;
    private final DemoCohortEvidence demoCohorts;
    private final OutcomeService outcomes;private final Diagnostics diagnostics;private final PerformanceService performance;
    public FeedbackService(Store store,InvestigationWorkflowService workflows,WorkflowService simulations,EngineGateway engine,OutcomeService outcomes){
        this.store=store;this.workflows=workflows;this.simulations=simulations;this.engine=engine;this.outcomes=outcomes;diagnostics=new Diagnostics(store,engine);performance=new PerformanceService(store,outcomes,engine,Integer.parseInt(System.getenv().getOrDefault("AI_FOLLOW_UP_DAYS","14")));demoCohorts=new DemoCohortEvidence(store,engine,performance);
        briefing=new PerformanceBriefing(store,workflows,performance,demoCohorts,diagnostics,engine);
        workflows.executor(this::execute);
    }
    JsonNode startBriefing(String analyst,JsonNode body){return briefing.start(analyst,body);}
    JsonNode recordDemoCohort(String analyst,JsonNode body){return demoCohorts.record(analyst,body);}
    InvestigationWorkflowService runtime(){return workflows;}
    public JsonNode publication(String id,String analyst){var p=store.get("PUBLICATION",id).data();if(!p.path("analyst").asText().equals(analyst))throw new Fault(404,"NOT_FOUND","Publication not found");return p;}
    public PerformanceService performanceApi(){return performance;}
    /** Compatibility dashboard reads are deterministic and never dispatch an agent. */
    public JsonNode performance(String id,String analyst,String mode){return performance.summary(id,analyst,mode);}
    /** Creates an independently budgeted compound investigation; scope is resolved before evidence is retrieved. */
    public JsonNode create(String publication,String analyst,JsonNode body){
        fields(body,"requestId","text","budget","sourceMode","scope","customerReference");
        JsonNode pub=publication==null||publication.isBlank()?obj():performance.publication(publication,analyst);
        String request=text(body,"requestId"),question=text(body,"text");require(request.matches("[A-Za-z0-9._:-]{1,80}")&&question.length()<=6000,"REQUEST_LIMIT","Invalid investigation identity or question");
        String id=hash(List.of(analyst,"feedback",request));var old=store.find("WORKFLOW",id);
        if(old.isPresent()){require(old.get().data().path("requestHash").asText().equals(old.get().data().has("measurementVersion")?hash(List.of(publication==null?"":publication,body)):hash(body)),"REQUEST_CONFLICT","Investigation identity changed");return workflows.view(old.get());}
        var budget=convert(body.path("budget"),Store.Budget.class);budget.validate();
        String mode=body.path("sourceMode").asText(body.at("/scope/sourceMode").asText("DEMO"));require(!body.at("/scope/sourceMode").isTextual()||body.at("/scope/sourceMode").asText().equals(mode),"SCOPE_CONFLICT","Observation sources disagree");require(Set.of("DEMO","OBSERVED_REAL").contains(mode),"SOURCE_MODE","Select an explicit source");
        require(!body.hasNonNull("scope")||body.path("scope").isObject(),"MEASUREMENT_SCOPE","scope must be an object");
        var requested=body.hasNonNull("scope")?(ObjectNode)body.path("scope").deepCopy():object();requested.put("sourceMode",mode);
        performance.refresh();if(!requested.hasNonNull("evidenceCutoff"))requested.put("evidenceCutoff",Instant.now().toString());
        // Validate dates and defaults immediately even when publication selection needs a material clarification.
        MeasurementScope.parse(requested,pub.isEmpty()?requested.path("offerVersion").asInt(1):PerformanceService.version(pub),Instant.now());
        var data=obj("id",id,"analyst",analyst,"kind","FEEDBACK","network","FEEDBACK","measurementVersion",MeasurementScope.VERSION,"requestHash",hash(List.of(publication==null?"":publication,body)),"intent",question,"publicationId",publication==null?"":publication,"sourceMode",mode,"requestedScope",requested,"customerReference",body.path("customerReference").asText(""),"selectedPublicationId",publication==null?"":publication,"requestedCustomerReference",body.path("customerReference").asText(""),
            "baseline",pub.at("/preview/configuration"),"lockedStages",List.of(),"revision",1,"state","FEEDBACK_PENDING","budget",budget,"createdAt",Instant.now().toString(),"steps",obj(),"evaluations",List.of(),"candidates",List.of(),"messages",List.of(obj("role","analyst","text",question)),"answers",List.of());
        store.budget(id,budget);return workflows.view(store.create("WORKFLOW",id,data));
    }
    /** Dispatches the Business briefing or resolves compound scope and reviews the two investigation evidence branches. */
    void execute(String id){
        var w=workflows.current(id);if(w.path("briefing").asBoolean()){briefing.execute(id);return;}if(!w.has("measurementVersion")){executeLegacy(id);return;}String analyst=w.path("analyst").asText();
        if(!w.has("resolvedScope")){
            var plan=workflows.autonomousAgent(id,Role.ORCHESTRATOR,"investigation-scope",obj("mode","FEEDBACK","question",w.path("intent"),"publicationId",w.path("publicationId"),"customerReference",w.path("customerReference"),"requestedScope",w.path("requestedScope"),"publications",performance.publications(analyst),"defaults",obj("dateBasis","FIRST_ASSESSED","timezone","America/Los_Angeles","variant","OFFER_FAMILY")));
            if(workflows.stopIfUnresolved(id,plan))return;
            var proposed=plan.decision().investigationPlan();require(proposed!=null,"INVESTIGATION_PLAN","The orchestrator must return a typed investigation plan before any lookup");
            String publication=proposed.publicationId();if(!w.path("publicationId").asText().isBlank()){require(Objects.equals(publication,w.path("publicationId").asText()),"SCOPE_CONFLICT","Investigation cannot switch the selected publication");}
            performance.publication(publication,analyst);var scope=(ObjectNode)tree(proposed);scope.remove(List.of("publicationId","customerReference","customerVariant","customerStartDate","customerEndDate"));
            w.path("requestedScope").fields().forEachRemaining(e->{if(scope.hasNonNull(e.getKey()))require(scope.path(e.getKey()).equals(e.getValue()),"SCOPE_CONFLICT","Plan conflicts with the explicitly selected "+e.getKey());scope.set(e.getKey(),e.getValue());});
            var resolved=performance.scope(publication,analyst,scope);
            String customer=proposed.customerReference()==null?"":proposed.customerReference().trim();if(!w.path("customerReference").asText().isBlank())require(customer.equals(w.path("customerReference").asText()),"SCOPE_CONFLICT","Plan conflicts with the selected customer");
            var customerScope=(ObjectNode)tree(resolved);customerScope.set("startDate",tree(proposed.customerStartDate()));customerScope.set("endDate",tree(proposed.customerEndDate()));if(proposed.customerVariant()!=null)customerScope.put("variant",proposed.customerVariant());performance.scope(publication,analyst,customerScope);
            final String customerRef=customer;var pub=performance.publication(publication,analyst);
            workflows.mutate(id,n->{n.put("publicationId",publication).put("customerReference",customerRef);n.set("resolvedScope",tree(resolved));n.set("customerScope",customerScope);n.set("baseline",pub.at("/preview/configuration"));n.set("plan",tree(plan));});
        }
        w=workflows.current(id);String publication=w.path("publicationId").asText();
        if(!w.has("measurement")){var measured=performance.query(publication,analyst,w.path("resolvedScope"));workflows.mutate(id,n->n.set("measurement",measured));}
        w=workflows.current(id);
        if(!w.has("customerTrace")){var traced=w.path("customerReference").asText().isBlank()?obj("result",obj("status","NOT_REQUESTED")):performance.trace(publication,analyst,w.path("customerReference").asText(),w.path("customerScope"));workflows.mutate(id,n->n.set("customerTrace",traced));}
        w=workflows.current(id);
        var context=obj("mode","FEEDBACK","question",w.path("intent"),"scope",w.path("resolvedScope"),"product",w.at("/baseline/offer/product"),"measurement",agentEvidence(w.path("measurement")),"customerTrace",w.path("customerTrace"));
        var retrieved=workflows.autonomousAgent(id,Role.RESEARCH,"investigation-evidence",context);if(workflows.stopIfUnresolved(id,retrieved))return;
        Result customer=null;if(!w.path("customerReference").asText().isBlank()){customer=workflows.autonomousAgent(id,Role.ELIGIBILITY,"investigation-customer",context);if(workflows.stopIfUnresolved(id,customer))return;}
        var analysisInput=context.deepCopy();analysisInput.set("retrieval",tree(retrieved.decision()));analysisInput.set("customerReview",customer==null?tree(null):tree(customer.decision()));
        var analysis=workflows.autonomousAgent(id,Role.ANALYZER,"investigation-metrics",analysisInput);if(workflows.stopIfUnresolved(id,analysis))return;
        var reviewInput=analysisInput.deepCopy();reviewInput.set("analysis",tree(analysis));
        var review=workflows.agent(id,Role.REFLECTION,"investigation-review",reviewInput);
        if(review.decision().status().equals("REVISE")){analysisInput.set("critique",tree(review.decision()));analysisInput.set("originalAnalysis",tree(analysis.decision()));analysis=workflows.autonomousAgent(id,Role.ANALYZER,"investigation-metrics-revision",analysisInput);if(workflows.stopIfUnresolved(id,analysis))return;reviewInput.set("analysis",tree(analysis.decision()));review=workflows.agent(id,Role.REFLECTION,"investigation-review-revision",reviewInput);}
        if(workflows.stopIfUnresolved(id,review))return;
        var answer=obj("scope",w.path("resolvedScope"),"measurement",w.path("measurement"),"customerTrace",w.path("customerTrace"),"summary",analysis.decision().summary(),"claims",analysis.decision().claims(),"review",review.decision().summary());
        var finalAnalysis=analysis;var finalReview=review;workflows.mutate(id,n->{n.set("analysis",tree(finalAnalysis));n.set("reflection",tree(finalReview));n.set("answer",answer);n.put("state","COMPLETED").put("completedAt",Instant.now().toString());});
    }
    /** Tool dispatch is restricted to the registered role and the workflow's frozen scope and snapshots. */
    public JsonNode tool(Role role,Request request,String name,JsonNode args){
        fields(args);workflows.authorizeTool(role,request);var w=workflows.current(request.workflowId());
        return switch(name){
            case "performance.evidence"->{require(role==Role.RESEARCH,"TOOL_DENIED","Evidence retrieval role required");yield metricEvidence(w);}
            case "eligibility.trace"->{require(role==Role.ELIGIBILITY,"TOOL_DENIED","Customer eligibility role required");yield w.path("customerTrace");}
            case "statistics.measure","statistics.compare"->{require(role==Role.ANALYZER,"TOOL_DENIED","Metrics analyst role required");yield metricEvidence(w);}
            case "evidence.review"->{require(role==Role.REFLECTION,"TOOL_DENIED","Evidence reviewer role required");yield obj("measurement",metricEvidence(w),"customerTrace",w.path("customerTrace"));}
            default->throw new Fault(422,"TOOL_DENIED","Unknown investigation tool");};
    }
    private static JsonNode metricEvidence(JsonNode workflow){
        if(workflow.has("briefingEvidence")){
            var evidence=workflow.path("briefingEvidence");
            var offers=MAPPER.createArrayNode();
            for(int index=0;index<Math.min(10,evidence.path("offers").size());index++)offers.add(evidence.path("offers").path(index));
            return obj("asOf",evidence.path("asOf"),"offers",offers,"totalActive",evidence.path("totalActive"));
        }
        return workflow.has("measurement")?agentEvidence(workflow.path("measurement")):workflow.path("performance");
    }
    /** Limits model-facing lists while preserving aggregate counts and the full immutable snapshot reference. */
    private static JsonNode agentEvidence(JsonNode value){
        var copy=value.deepCopy();var observed=copy.at("/result/comparison/observed");
        if(observed.isObject())for(String field:List.of("dailyCounts","evidenceIds")){var all=observed.path(field);var sample=MAPPER.createArrayNode();for(int i=0;i<Math.min(all.size(),20);i++)sample.add(all.path(i));((ObjectNode)observed).set(field,sample);((ObjectNode)observed).put(field+"Truncated",all.size()>20);}
        return copy;
    }
    /** Retrieves fresh bounded evidence, then runs research, analysis and a separate challenge step over the immutable snapshot. */
    private void executeLegacy(String id){
        var w=workflows.current(id);String analyst=w.path("analyst").asText(),catalog=w.path("publicationId").asText(),product=w.path("baseline").path("offer").path("product").asText();
        if(!w.has("performance")){var value=performance(catalog,analyst,w.path("sourceMode").asText());workflows.mutate(id,n->n.set("performance",value));}
        w=workflows.current(id);
        if(!w.has("diagnostics")){var value=diagnostics.collect(catalog,w.path("sourceMode").asText(),Math.max(5,w.path("budget").path("minimumEligible").asInt()));workflows.mutate(id,n->n.set("diagnostics",value));}
        w=workflows.current(id);
        var plan=workflows.autonomousAgent(id,Role.ORCHESTRATOR,"feedback-plan",obj("mode","FEEDBACK","question",w.path("intent"),"product",product,"lockedStages",w.path("lockedStages"),"comparison",w.path("performance"),"diagnostics",w.path("diagnostics")));
        if(workflows.stopIfUnresolved(id,plan))return;
        var research=workflows.autonomousAgent(id,Role.RESEARCH,"feedback-research",obj("mode","FEEDBACK","intent",w.path("intent"),"product",product,"comparison",w.path("performance"),"diagnostics",w.path("diagnostics"),"plan",plan,"lockedStages",w.path("lockedStages")));
        if(workflows.stopIfUnresolved(id,research))return;
        var analysis=workflows.agent(id,Role.ANALYZER,"feedback-analysis",obj("analysisMode","FEEDBACK","comparison",w.path("performance"),"diagnostics",w.path("diagnostics"),"research",research,"question",w.path("intent"),"lockedStages",w.path("lockedStages")));
        var review=workflows.agent(id,Role.REFLECTION,"feedback-review",obj("reviewType","FEEDBACK","analysis",analysis,"comparison",w.path("performance"),"diagnostics",w.path("diagnostics"),"research",research));
        if(review.decision().status().equals("REVISE")){
            analysis=workflows.agent(id,Role.ANALYZER,"feedback-revision",obj("analysisMode","FEEDBACK","critique",review,"original",analysis,"comparison",w.path("performance"),"diagnostics",w.path("diagnostics"),"research",research));
            review=workflows.agent(id,Role.REFLECTION,"feedback-revision-review",obj("reviewType","FEEDBACK","analysis",analysis,"comparison",w.path("performance"),"diagnostics",w.path("diagnostics"),"research",research));
        }
        var finalAnalysis=analysis;var finalReview=review;
        workflows.mutate(id,n->{n.set("analysis",tree(finalAnalysis));n.set("reflection",tree(finalReview));n.put("state",finalAnalysis.decision().status().equals("READY")&&finalReview.decision().status().equals("READY")?"COMPLETED":"REVIEW_REQUIRED").put("completedAt",Instant.now().toString());});
    }
    /** Freezes a fresh published baseline and rotates the outer validation population for every successor. */
    public JsonNode successor(String publication,String analyst,JsonNode body){
        fields(body,"requestId","intent","populationId","validationPopulationId","budget","lockedStages","seed","autoExecute");
        var pub=publication(publication,analyst);String request=text(body,"requestId");var original=simulations.get(pub.path("workflowId").asText(),analyst).data();
        var locks=WorkflowService.strings(original.path("lockedStages"));locks.addAll(WorkflowService.strings(body.path("lockedStages")));
        var draft=engine.successor(pub,"successor:"+hash(List.of(analyst,request)));
        var input=(ObjectNode)body.deepCopy();input.put("draftId",text(draft,"id")).put("predecessorPublicationId",publication);input.set("lockedStages",tree(locks));
        return simulations.create(input,analyst);
    }
    /** Monitoring uses single-time outcome snapshots and records changes without authorizing model spend or publication. */
    public void monitor(){
        String after="";for(;;){var page=store.list("PUBLICATION",after,100);
            for(var pub:page)try{
                var metrics=outcomes.performanceNow(pub.id(),pub.data().path("sourceMode").asText("DEMO"));
                var key=obj("maturity",metrics.path("maturity"),"coverage",metrics.path("coverage"),"supportReached",metrics.path("E").asInt()>=30,"band",metrics.path("acceptanceAmongEligiblePct").isNull()?null:(int)(metrics.path("acceptanceAmongEligiblePct").asDouble()/5));
                var previous=store.find("MONITOR",pub.id());
                if(previous.isEmpty()||!previous.get().data().path("key").equals(key)){
                    var event=obj("publicationId",pub.id(),"analyst",pub.data().path("analyst"),"type","FEEDBACK_REVIEW_AVAILABLE","createdAt",Instant.now().toString(),"metricsRef",store.artifact((Object)metrics));
                    store.create("NOTICE",id(),event);
                    var next=obj("key",key,"checkedAt",Instant.now().toString());if(previous.isEmpty())store.create("MONITOR",pub.id(),next);else store.update("MONITOR",pub.id(),previous.get().version(),next);
                }
            }catch(Exception ignored){}
            if(page.size()<100)break;after=page.get(page.size()-1).id();
        }
    }
}
