package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import static com.lending.ai.Json.*;
import static com.lending.ai.Contracts.*;

/** Durable performance, research, analysis and reflection loop; revisions create fresh, locked successor experiments. */
public final class FeedbackService {
    private final Store store;private final WorkflowService workflows;private final EngineGateway engine;
    private final OutcomeService outcomes;private final Diagnostics diagnostics;
    public FeedbackService(Store store,WorkflowService workflows,EngineGateway engine,OutcomeService outcomes){
        this.store=store;this.workflows=workflows;this.engine=engine;this.outcomes=outcomes;diagnostics=new Diagnostics(store,engine);workflows.feedback(this);
    }
    public JsonNode publication(String id,String analyst){var p=store.get("PUBLICATION",id).data();if(!p.path("analyst").asText().equals(analyst))throw new Fault(404,"NOT_FOUND","Publication not found");return p;}
    /** Produces the answer from stored predictions and source facts; no language model invents either rate. */
    public JsonNode performance(String id,String analyst,String mode){
        var p=publication(id,analyst);JsonNode sync;
        try{sync=outcomes.synchronize();}catch(Exception e){sync=obj("status","SOURCE_UNAVAILABLE");}
        var prediction=mode.equals("OBSERVED_REAL")?store.find("REAL_PREDICTION",id).map(Store.Saved::data).orElse(p.path("prediction")):p.path("prediction");
        var observed=outcomes.performance(id,Instant.now().toString(),mode);var comparison=outcomes.compare(prediction,observed);
        String ref=store.artifact((Object)comparison);
        String rate=observed.path("acceptanceAmongEligiblePct").isNull()?"unavailable":String.format(Locale.ROOT,"%.2f%%",observed.path("acceptanceAmongEligiblePct").asDouble());
        String estimate=prediction.path("estimatePct").isNumber()?String.format(Locale.ROOT,"%.2f%%",prediction.path("estimatePct").asDouble()):"unavailable";
        return obj("snapshotRef",ref,"comparison",comparison,"sourceRefresh",sync,
            "answer","As of "+observed.path("asOf").asText()+", "+observed.path("Y").asInt()+" of "+observed.path("E").asInt()+" eligible customers selected this offer family: "+rate+
                ". The saved estimate was "+estimate+" ("+prediction.path("predictionKind").asText()+"). Source: "+mode+
                "; maturity: "+observed.path("maturity").asText()+"; comparison: "+comparison.path("comparability").asText()+".");
    }
    public JsonNode create(String publication,String analyst,JsonNode body){
        fields(body,"requestId","text","budget","sourceMode");var p=publication(publication,analyst);
        String request=text(body,"requestId"),question=text(body,"text");require(request.length()<=80&&question.length()<=6000,"REQUEST_LIMIT","Feedback request is too large");
        String id=hash(List.of(analyst,"feedback",request));var old=store.find("WORKFLOW",id);
        if(old.isPresent()){require(old.get().data().path("requestHash").asText().equals(hash(body)),"REQUEST_CONFLICT","Feedback request identity changed");return workflows.view(old.get());}
        String mode=body.path("sourceMode").asText("DEMO");require(Set.of("DEMO","OBSERVED_REAL").contains(mode),"SOURCE_MODE","Choose explicit source mode");
        var budget=convert(body.path("budget"),Store.Budget.class);budget.validate();
        var original=workflows.get(p.path("workflowId").asText(),analyst).data();
        var data=obj("id",id,"analyst",analyst,"kind","FEEDBACK","requestHash",hash(body),"intent",question,"publicationId",publication,"sourceMode",mode,
            "baseline",p.path("preview").path("configuration"),"lockedStages",original.path("lockedStages"),"revision",1,"state","FEEDBACK_PENDING","budget",budget,
            "createdAt",Instant.now().toString(),"steps",obj(),"evaluations",List.of(),"candidates",List.of(),"messages",List.of(obj("role","analyst","text",question)));
        store.budget(id,budget);return workflows.view(store.create("WORKFLOW",id,data));
    }
    /** Retrieves fresh bounded evidence, then runs research, analysis and a separate challenge step over the immutable snapshot. */
    void execute(String id){
        var w=workflows.current(id);String analyst=w.path("analyst").asText(),catalog=w.path("publicationId").asText(),product=w.path("baseline").path("offer").path("product").asText();
        if(!w.has("performance")){var value=performance(catalog,analyst,w.path("sourceMode").asText());workflows.mutate(id,n->n.set("performance",value));}
        w=workflows.current(id);
        if(!w.has("diagnostics")){var value=diagnostics.collect(catalog,w.path("sourceMode").asText(),Math.max(5,w.path("budget").path("minimumEligible").asInt()));workflows.mutate(id,n->n.set("diagnostics",value));}
        w=workflows.current(id);
        var plan=workflows.agent(id,Role.ORCHESTRATOR,"feedback-plan",obj("mode","FEEDBACK","question",w.path("intent"),"product",product,"lockedStages",w.path("lockedStages"),"comparison",w.path("performance"),"diagnostics",w.path("diagnostics")));
        if(needsInput(id,plan))return;
        var research=workflows.agent(id,Role.RESEARCH,"feedback-research",obj("mode","FEEDBACK","intent",w.path("intent"),"product",product,"comparison",w.path("performance"),"diagnostics",w.path("diagnostics"),"plan",plan,"lockedStages",w.path("lockedStages")));
        if(needsInput(id,research))return;
        var analysis=workflows.agent(id,Role.ANALYZER,"feedback-analysis",obj("analysisMode","FEEDBACK","comparison",w.path("performance"),"diagnostics",w.path("diagnostics"),"research",research,"question",w.path("intent"),"lockedStages",w.path("lockedStages")));
        var review=workflows.agent(id,Role.REFLECTION,"feedback-review",obj("reviewType","FEEDBACK","analysis",analysis,"comparison",w.path("performance"),"diagnostics",w.path("diagnostics"),"research",research));
        if(review.decision().status().equals("REVISE")){
            analysis=workflows.agent(id,Role.ANALYZER,"feedback-revision",obj("analysisMode","FEEDBACK","critique",review,"original",analysis,"comparison",w.path("performance"),"diagnostics",w.path("diagnostics"),"research",research));
            review=workflows.agent(id,Role.REFLECTION,"feedback-revision-review",obj("reviewType","FEEDBACK","analysis",analysis,"comparison",w.path("performance"),"diagnostics",w.path("diagnostics"),"research",research));
        }
        var finalAnalysis=analysis;var finalReview=review;
        workflows.mutate(id,n->{n.set("analysis",tree(finalAnalysis));n.set("reflection",tree(finalReview));n.put("state",finalAnalysis.decision().status().equals("READY")&&finalReview.decision().status().equals("READY")?"COMPLETED":"REVIEW_REQUIRED").put("completedAt",Instant.now().toString());});
    }
    private boolean needsInput(String id,Result result){
        if(result.decision().status().equals("READY"))return false;
        workflows.mutate(id,n->{n.put("state","NEEDS_INPUT");n.put("clarification",result.decision().clarification()==null?result.decision().summary():result.decision().clarification());});return true;
    }
    /** Freezes a fresh published baseline and rotates the outer validation population for every successor. */
    public JsonNode successor(String publication,String analyst,JsonNode body){
        fields(body,"requestId","intent","populationId","validationPopulationId","budget","lockedStages","seed");
        var pub=publication(publication,analyst);String request=text(body,"requestId");var original=workflows.get(pub.path("workflowId").asText(),analyst).data();
        var locks=WorkflowService.strings(original.path("lockedStages"));locks.addAll(WorkflowService.strings(body.path("lockedStages")));
        var draft=engine.successor(pub,"successor:"+hash(List.of(analyst,request)));
        var input=(ObjectNode)body.deepCopy();input.put("draftId",text(draft,"id")).put("predecessorPublicationId",publication);input.set("lockedStages",tree(locks));
        return workflows.create(input,analyst);
    }
    /** Monitoring records actionable changes without authorizing model spend, new simulations or publication. */
    public void monitor(){
        String after="";for(;;){var page=store.list("PUBLICATION",after,100);
            for(var pub:page)try{
                var metrics=outcomes.performance(pub.id(),Instant.now().toString(),pub.data().path("sourceMode").asText("DEMO"));
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

