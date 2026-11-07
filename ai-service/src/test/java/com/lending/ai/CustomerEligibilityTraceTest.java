package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.lending.ai.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/** Historical traces distinguish profile versions, exact variants and assessment dates from later storage refresh dates. */
class CustomerEligibilityTraceTest {
    static JsonNode event(String id,String status){return obj("eventId",id,"sequence",1,"occurredAt","2026-09-05T08:00:00Z","offer",obj("id","family","customerId","customer","qualification",obj("catalogOfferId","published","catalogOfferVersion",1,"responseVersion",2,"qualificationId","catalog-qualification","eligibilityPolicyVersion","rules-7"),"preScreen",obj("status","ACTIVE","terms",obj("apr",10),"validUntil","2026-09-15T08:00:00Z"),"marketing",obj("status",status,"terms",obj("apr",8),"variantQualificationId","personal-qualification","validUntil","2026-09-15T08:00:00Z")));}
    static EngineGateway owner(){return new EngineGateway("http://127.0.0.1:1","unused"){
        public JsonNode get(String path){
            if(path.startsWith("customers/resolve"))return obj("customerId","customer");
            if(path.equals("customers/customer"))return obj("profiles",List.of(obj("version",1),obj("version",2),obj("version",3)));
            if(path.startsWith("decision-responses/family/history"))return tree(List.of(obj("response",obj("id","family","version",2,"customerId","customer","qualification",obj("offerId","published","offerVersion",1,"profileVersion",2),"evaluatedAt","2026-09-01T08:00:00Z"))));
            throw new AssertionError(path);
        }
    };}
    static MeasurementScope scope(JsonNode extra){var base=obj("sourceMode","DEMO","evidenceCutoff","2026-09-30T12:00:00Z");extra.fields().forEachRemaining(e->base.set(e.getKey(),e.getValue()));return MeasurementScope.parse(base,1,PerformanceMeasurementsTest.CUTOFF);}
    @Test void oldProfileIsTracedWithoutSubstitutingTheLatestAndStorageTimeIsNotAssessmentTime(){try(var s=new FoundationTest().store()){
        var outcome=new OutcomeService(s,null,14,Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"),ZoneOffset.UTC));outcome.ingestStorage(event("native-1","ACTIVE"));
        var tracer=new CustomerEligibilityTrace(s,owner(),14);var personal=tracer.trace("published","DEMO-0001",scope(obj("variant","PERSONALIZED")));assertEquals("ELIGIBLE",personal.path("eligibility").asText());assertEquals(2,personal.path("profileVersion").asInt());assertTrue(personal.path("profileHistoryVerified").asBoolean());assertEquals("personal-qualification",personal.path("qualificationId").asText());assertTrue(personal.path("assessedAt").isNull());
        var original=tracer.trace("published","DEMO-0001",scope(obj("variant","ORIGINAL","startDate","2026-09-01","endDate","2026-09-01")));assertEquals("ELIGIBLE",original.path("eligibility").asText());assertEquals("2026-09-01T08:00:00Z",original.path("assessedAt").asText());
        assertEquals("NOT_RECORDED",tracer.trace("published","DEMO-0001",scope(obj("variant","ORIGINAL","startDate","2026-09-05","endDate","2026-09-05"))).path("eligibility").asText());
        assertEquals("NOT_RECORDED",tracer.trace("published","DEMO-0001",scope(obj("variant","PERSONALIZED","startDate","2026-09-01","endDate","2026-09-01"))).path("eligibility").asText());
    }}
    @Test void unknownReceiptTimeAndLifecycleStatusCannotBecomeNegativeCreditDecisions(){try(var s=new FoundationTest().store()){
        s.create("OUTCOME_EVENT","legacy",event("legacy","ACTIVE"));var tracer=new CustomerEligibilityTrace(s,owner(),14);assertEquals("NOT_RECORDED",tracer.trace("published","DEMO-0001",scope(obj())).path("eligibility").asText());
        new OutcomeService(s,null,14,Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"),ZoneOffset.UTC)).ingestStorage(event("native-2","EXPIRED"));var trace=tracer.trace("published","DEMO-0001",scope(obj("variant","PERSONALIZED")));assertEquals("NOT_RECORDED",trace.path("eligibility").asText());assertEquals("EXPIRED",trace.path("recordedLifecycleStatus").asText());
        assertEquals("NOT_RECORDED",tracer.trace("published","DEMO-0001",scope(obj("evidenceCutoff","2026-09-05T12:00:00Z"))).path("eligibility").asText());
    }}
}
