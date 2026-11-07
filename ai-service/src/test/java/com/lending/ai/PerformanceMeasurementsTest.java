package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.lending.ai.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/** Recorded fixture facts exercise cohort math, exact variants, correction cutoffs and missing-history semantics. */
class PerformanceMeasurementsTest {
    static final Instant CUTOFF=Instant.parse("2026-09-30T12:00:00Z");
    static String subject(int i){return hash("customer-"+i);}
    static void publication(Store s){s.create("PUBLICATION","published",obj("analyst","analyst","preview",obj("receipt",obj("offerVersion",1),"configuration",obj("offer",obj("name","Personalized auto loan","product","AUTO_LOAN"))),"prediction",obj("estimatePct",80,"eligibilityPct",90,"sourceMode","DEMO","scope","CATALOG_TERMS_ONLY","predictionKind","SIMULATED_UTILITY")));}
    static void fact(Store s,String kind,String customer,String at,JsonNode data){fact(s,kind,customer,at,data,1,"2026-09-20T00:00:00Z");}
    static void fact(Store s,String kind,String customer,String at,JsonNode data,int revision,String recorded){s.create("SOURCE_EVENT",id(),obj("sourceId","test-source","sourceMode","OBSERVED_REAL","ingestedAt",recorded,"event",obj("eventId",kind+":"+customer+":"+data.path("businessVariant").asText()+":"+revision,"publicationId","published","kind",kind,"customerKey",customer,"businessKey",customer+(data.has("businessVariant")?":"+data.path("businessVariant").asText():""),"revision",revision,"occurredAt",at,"data",data)));}
    static void fixture(Store s){publication(s);for(int i=0;i<12;i++){
        String at=i<9?"2026-09-01T08:00:00Z":"2026-09-02T08:00:00Z";
        fact(s,"ASSESSMENT",subject(i),at,obj("status",i<10?"ELIGIBLE":"INELIGIBLE","profileVersion",3,"policyVersion",7,"catalogOfferVersion",1,"variant","OFFER_FAMILY"));
        if(i==0)fact(s,"ASSESSMENT",subject(i),at,obj("status","ELIGIBLE","profileVersion",3,"policyVersion",7,"catalogOfferVersion",1,"variant","PERSONALIZED","variantQualificationId","qualified-personal-0","businessVariant","PERSONALIZED"));
        if(i<10)fact(s,"ENROLLMENT",subject(i),at,obj("familyId","family-"+i,"terms",obj("apr",7),"followUpEndsAt",Instant.parse(at).plus(Duration.ofDays(14)).toString(),"variant",i==0?"PERSONALIZED":"ORIGINAL","variantQualificationId",i==0?"qualified-personal-0":null));
        if(i<3||i==9)fact(s,"SELECTION",subject(i),Instant.parse(at).plusSeconds(60).toString(),obj("familyId","family-"+i,"kind",i==0?"PERSONALIZED":"ORIGINAL","terms",obj("apr",7)));
    }fact(s,"WATERMARK","published","2026-09-30T12:00:00Z",obj("complete",true,"includesAllAdmissions",true,"followUpDays",14,"entryStartsAt","2026-09-01T07:00:00Z","entryEndsAt","2026-09-03T07:00:00Z","through","2026-09-30T12:00:00Z"));}
    static MeasurementScope scope(JsonNode extra){var input=obj("startDate","2026-09-01","endDate","2026-09-02","sourceMode","OBSERVED_REAL","evidenceCutoff",CUTOFF.toString());extra.fields().forEachRemaining(e->input.set(e.getKey(),e.getValue()));return MeasurementScope.parse(input,1,CUTOFF);}
    @Test void weightedCountsPreservePredictionAndGateForecastErrors(){try(var s=new FoundationTest().store()){
        fixture(s);var measurements=new PerformanceMeasurements(s,14);var result=measurements.query("published",scope(obj()));
        assertEquals(12,result.path("assessedCount").asInt());assertEquals(10,result.path("E").asInt());assertEquals(4,result.path("Y").asInt());assertEquals(40,result.path("acceptanceAmongEligiblePct").asDouble());assertEquals(100d*10/12,result.path("eligibilityPct").asDouble());assertEquals(2,result.path("dailyCounts").size());assertEquals("MATURE",result.path("maturity").asText());assertEquals("OPERATIONAL_SOURCE_RECONCILED",result.path("coverage").asText());
        var outcomes=new OutcomeService(s,null,14);String original=hash(s.get("PUBLICATION","published").data());assertTrue(outcomes.compare(s.get("PUBLICATION","published").data().path("prediction"),result).path("gapPercentagePoints").isNull());
        var prediction=obj("estimatePct",80,"predictionKind","CALIBRATED_SELECTION","sourceMode","OBSERVED_REAL","scope","OFFER_FAMILY","followUpDays",14,"eligibleCohortHash",result.path("eligibleCohortHash"),"generatedAt","2026-08-31T00:00:00Z");var comparison=outcomes.compare(prediction,result);assertEquals("MATCH",comparison.path("comparability").asText());assertEquals(-40,comparison.path("gapPercentagePoints").asDouble());assertEquals(original,hash(s.get("PUBLICATION","published").data()));
        assertEquals("COHORT_MISMATCH",outcomes.compare(prediction,measurements.query("published",scope(obj("endDate","2026-09-01")))).path("comparability").asText());
    }}
    @Test void lateCorrectionsCannotRewriteAnEarlierCutoffAndPersonalizedUsesExactTerms(){try(var s=new FoundationTest().store()){
        fixture(s);fact(s,"ASSESSMENT",subject(0),"2026-09-01T08:00:00Z",obj("status","INELIGIBLE","variant","OFFER_FAMILY"),2,"2026-10-01T00:00:00Z");var metrics=new PerformanceMeasurements(s,14);
        assertEquals(10,metrics.query("published",scope(obj())).path("E").asInt());
        var personalized=scope(obj("variant","PERSONALIZED","dateBasis","FIRST_ELIGIBLE"));assertEquals(1,metrics.query("published",personalized).path("E").asInt());assertEquals(1,metrics.query("published",personalized).path("Y").asInt());
        fact(s,"SELECTION",subject(0),"2026-09-01T08:01:00Z",obj("familyId","family-0","kind","PERSONALIZED","terms",obj("apr",12)),2,"2026-09-29T00:00:00Z");assertEquals(0,metrics.query("published",personalized).path("Y").asInt());
        var later=MeasurementScope.parse(obj("startDate","2026-09-01","endDate","2026-09-02","sourceMode","OBSERVED_REAL","evidenceCutoff","2026-10-02T00:00:00Z"),1,Instant.parse("2026-10-02T00:00:00Z"));assertEquals(9,metrics.query("published",later).path("E").asInt());
    }}
    @Test void localInclusiveDatesHandleDstAndRejectInvalidScopes(){
        var s=MeasurementScope.parse(obj("startDate","2026-03-08","endDate","2026-03-08"),1,CUTOFF);assertEquals(23,Duration.between(s.start(),s.end()).toHours());assertEquals("America/Los_Angeles",s.timezone());assertEquals("FIRST_ASSESSED",s.dateBasis());assertTrue(s.includes("2026-03-08T08:00:00Z"));assertFalse(s.includes("2026-03-09T07:00:00Z"));assertThrows(Fault.class,()->MeasurementScope.parse(obj("startDate","2026-03-08"),1,CUTOFF));assertThrows(Fault.class,()->MeasurementScope.parse(obj("offerVersion",2),1,CUTOFF));assertThrows(Fault.class,()->MeasurementScope.parse(obj("timezone","invented"),1,CUTOFF));
    }
    @Test void missingAdmissionsAndExactHistoryStayUnknown(){try(var s=new FoundationTest().store()){
        publication(s);var metrics=new PerformanceMeasurements(s,14);var empty=metrics.query("published",scope(obj()));assertTrue(empty.path("assessedCount").isNull());assertTrue(empty.path("acceptanceAmongEligiblePct").isNull());assertTrue(empty.path("eligibilityPct").isNull());
        var tracer=new CustomerEligibilityTrace(s,null,14);assertEquals("NOT_RECORDED",tracer.trace("published","DEMO-0001",scope(obj("variant","PERSONALIZED"))).path("eligibility").asText());
        fact(s,"ASSESSMENT",subject(0),"2026-09-01T08:00:00Z",obj("status","ELIGIBLE","variant","ORIGINAL","profileVersion",3,"policyVersion",7));assertEquals("NOT_RECORDED",tracer.trace("published",subject(0),scope(obj("variant","PERSONALIZED"))).path("eligibility").asText());
        var trace=tracer.trace("published",subject(0),scope(obj("variant","ORIGINAL")));assertEquals("ELIGIBLE",trace.path("eligibility").asText());assertEquals(3,trace.path("profileVersion").asInt());assertTrue(metrics.query("published",scope(obj())).path("acceptanceAmongEligiblePct").isNull());
    }}
}
