package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import static com.lending.ai.Json.*;

/** Model-free performance owner: immutable predictions, scoped counts, historical decisions and evidence snapshots. */
public final class PerformanceService {
    private final Store store;private final OutcomeService outcomes;private final PerformanceMeasurements measurements;private final CustomerEligibilityTrace eligibility;
    public PerformanceService(Store store,OutcomeService outcomes,EngineGateway engine,int days){this.store=store;this.outcomes=outcomes;measurements=new PerformanceMeasurements(store,days);eligibility=new CustomerEligibilityTrace(store,engine,days);}
    public JsonNode publication(String id,String analyst){require(id!=null&&!id.isBlank()&&id.length()<=120,"PUBLICATION_REQUIRED","Select a recorded publication");var p=store.get("PUBLICATION",id).data();if(!p.path("analyst").asText().equals(analyst))throw new Fault(404,"NOT_FOUND","Publication not found");return p;}
    public List<Store.Saved> publicationPage(String analyst,String after){var result=new ArrayList<Store.Saved>();for(;;){var page=store.list("PUBLICATION",after,100);for(var p:page)if(p.data().path("analyst").asText().equals(analyst)){result.add(p);if(result.size()==20)return result;}if(page.size()<100)return result;after=page.get(page.size()-1).id();}}
    public List<JsonNode> publications(String analyst){var rows=new ArrayList<JsonNode>();String after="";for(;;){var page=store.list("PUBLICATION",after,100);for(var p:page)if(p.data().path("analyst").asText().equals(analyst))rows.add(obj("id",p.id(),"name",p.data().at("/preview/configuration/offer/name"),"product",p.data().at("/preview/configuration/offer/product"),"offerVersion",version(p.data())));if(page.size()<100)return rows;after=page.get(page.size()-1).id();}}
    /** Resolves only immutable source receipts belonging to an analyst-owned publication. */
    public JsonNode record(String publication,String analyst,String reference){
        publication(publication,analyst);var records=new RecordedEvidence(store);
        if(reference.startsWith("storage:")){var saved=store.find("OUTCOME_EVENT",reference.substring(8));if(saved.isPresent()&&saved.get().data().at("/offer/qualification/catalogOfferId").asText().equals(publication))return saved.get().data();}
        if(reference.startsWith("source:"))for(var row:records.all("SOURCE_EVENT"))if(row.at("/event/publicationId").asText().equals(publication)&&reference.equals("source:"+row.path("sourceId").asText()+":"+row.at("/event/eventId").asText()))return row;
        throw new Fault(404,"NOT_FOUND","Evidence record not found for this publication");
    }
    public JsonNode refresh(){try{return outcomes.synchronize();}catch(Exception e){return obj("status","SOURCE_UNAVAILABLE","limitation","Showing only previously recorded evidence.");}}
    public static int version(JsonNode publication){return publication.at("/preview/receipt/offerVersion").asInt(1);}
    public MeasurementScope scope(String id,String analyst,JsonNode input){return MeasurementScope.parse(input,version(publication(id,analyst)),Instant.now());}
    /** Uses deterministic combined counts and only exposes a forecast gap when all comparison gates match. */
    public JsonNode query(String id,String analyst,JsonNode input){var p=publication(id,analyst);var refreshed=refresh();var scope=scope(id,analyst,input);var observed=measurements.query(id,scope);
        JsonNode prediction=scope.sourceMode().equals("OBSERVED_REAL")?store.find("REAL_PREDICTION",id).map(Store.Saved::data).orElse(p.path("prediction")):p.path("prediction");
        var comparison=outcomes.compare(prediction,observed);var frozen=originalEligibility(p);
        var result=obj("measurementVersion",MeasurementScope.VERSION,"scope",scope,"comparison",comparison,"originalPrediction",p.path("prediction"),"eligibilityComparison",obj("predictedPct",frozen,"observedPct",observed.path("eligibilityPct"),"comparability","DIAGNOSTIC_ONLY","gapPercentagePoints",null,"limitation","Synthetic validation eligibility and an operational cohort are different populations."),"sourceRefresh",refreshed);
        String ref=store.artifact((Object)result);return obj("snapshotRef",ref,"result",result);
    }
    public JsonNode trace(String id,String analyst,String customer,JsonNode input){publication(id,analyst);var value=eligibility.trace(id,customer,scope(id,analyst,input));return obj("snapshotRef",store.artifact((Object)value),"result",value);}
    private JsonNode originalEligibility(JsonNode p){if(p.path("prediction").hasNonNull("eligibilityPct"))return p.path("prediction").path("eligibilityPct");var w=store.find("WORKFLOW",p.path("workflowId").asText());if(w.isPresent())for(var e:w.get().data().path("evaluations"))if(e.path("runId").equals(p.at("/preview/runId")))return e.at("/score/eligibilityPct");return tree(null);}
    public JsonNode summary(String id,String analyst,String mode){
        var p=publication(id,analyst);JsonNode sync;
        try{sync=outcomes.synchronize();}catch(Exception e){sync=obj("status","SOURCE_UNAVAILABLE");}
        var prediction=mode.equals("OBSERVED_REAL")?store.find("REAL_PREDICTION",id).map(Store.Saved::data).orElse(p.path("prediction")):p.path("prediction");
        var observed=outcomes.performanceNow(id,mode);var comparison=outcomes.compare(prediction,observed);
        String ref=store.artifact((Object)comparison);
        String rate=observed.path("acceptanceAmongEligiblePct").isNull()?"unavailable":String.format(Locale.ROOT,"%.2f%%",observed.path("acceptanceAmongEligiblePct").asDouble());
        String estimate=prediction.path("estimatePct").isNumber()?String.format(Locale.ROOT,"%.2f%%",prediction.path("estimatePct").asDouble()):"unavailable";
        return obj("snapshotRef",ref,"comparison",comparison,"sourceRefresh",sync,"eligibilityComparison",obj("predictedPct",originalEligibility(p),"observedPct",observed.path("eligibilityPct"),"comparability","DIAGNOSTIC_ONLY"),
            "answer","As of "+observed.path("asOf").asText()+", "+observed.path("Y").asInt()+" of "+observed.path("E").asInt()+" eligible customers selected this offer family: "+rate+
                ". The saved estimate was "+estimate+" ("+prediction.path("predictionKind").asText()+"). Source: "+mode+
                "; maturity: "+observed.path("maturity").asText()+"; comparison: "+comparison.path("comparability").asText()+".");
    }

}
