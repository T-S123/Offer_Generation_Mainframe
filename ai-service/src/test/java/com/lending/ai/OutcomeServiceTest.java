package com.lending.ai;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.lending.ai.Json.*;

/** Verifies historical counts and single-time feedback snapshots under backward clock movement while preserving future-cutoff rejection. */
class OutcomeServiceTest {
    @Test void currentFeedbackUsesOneClockReadingEvenWhenTheClockMovesBackwards(){
        Instant initial=Instant.parse("2026-09-25T00:00:00Z");
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        Clock backwards=new Clock(){
            public ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return initial.minusSeconds(reads.getAndIncrement());}
        };
        try(var s=new FoundationTest().store()){
            var outcomes=new OutcomeService(s,null,14,backwards);
            outcomes.ingestStorage(event("one",1,"AWAITING_CREATION",false));
            outcomes.ingestStorage(event("two",2,"ACTIVE",true));
            s.create("CURSOR","storage",obj("sequence",2,"checkedAt",initial.minusSeconds(60).toString(),"completePage",true));
            s.create("PUBLICATION","catalog",obj("analyst","analyst","prediction",obj("sourceMode","DEMO","scope","CATALOG_TERMS_ONLY","predictionKind","SIMULATED_UTILITY","estimatePct",80)));
            try(var workflows=new WorkflowService(s,null,Map.of())){
                var feedback=new FeedbackService(s,workflows,null,outcomes);
                var observed=feedback.performance("catalog","analyst","DEMO").at("/comparison/observed");
                assertEquals(initial.toString(),observed.path("asOf").asText());
                assertEquals(1,reads.get(),"Cutoff validation and freshness must reuse the same clock reading");
                assertEquals(1,observed.path("E").asInt());assertEquals(1,observed.path("Y").asInt());
                assertEquals("STORAGE_HISTORY_RECONCILED",observed.path("coverage").asText());
                assertEquals(initial.minusSeconds(1).toString(),outcomes.performanceNow("catalog","DEMO").path("asOf").asText());
                assertEquals(2,reads.get());
                feedback.monitor();
                assertTrue(s.find("MONITOR","catalog").isPresent(),"Clock movement must not silently suppress monitoring");
                assertEquals(3,reads.get());
            }
        }
    }
    @Test void explicitFutureCutoffsStillFailWithoutAClockSkewAllowance(){
        Instant now=Instant.parse("2026-09-25T00:00:00Z");
        try(var s=new FoundationTest().store()){
            var outcomes=new OutcomeService(s,null,14,Clock.fixed(now,ZoneOffset.UTC));
            assertEquals("AS_OF_FUTURE",assertThrows(Fault.class,()->outcomes.performance("catalog",now.plusNanos(1).toString(),"DEMO")).code);
            assertDoesNotThrow(()->outcomes.performance("catalog",now.toString(),"DEMO"));
            assertDoesNotThrow(()->outcomes.performance("catalog",now.minusSeconds(1).toString(),"DEMO"));
        }
    }
    com.fasterxml.jackson.databind.JsonNode event(String id,int version,String state,boolean selected){
        var terms=obj("amountUsd",10000,"aprPct",10,"annualFeeUsd",0,"termMonths",36);
        return obj("eventId",id,"sequence",version,"occurredAt",version==1?"2026-09-01T00:00:00Z":"2026-09-03T00:00:00Z",
            "offer",obj("id","family","customerId","customer","qualification",obj("catalogOfferId","catalog","catalogOfferVersion",1,"responseVersion",version),
                "preScreen",obj("status",state,"validUntil","2026-09-20T00:00:00Z","terms",terms),
                "selection",selected?obj("requestId","selection","selectedAt","2026-09-02T00:00:00Z","kind","ORIGINAL","terms",terms,"available",false):null));
    }
    @Test void expiryAndReplayDoNotChangeHistoricalNumeratorOrDenominator(){
        try(var s=new Store("jdbc:h2:mem:"+UUID.randomUUID()+";DATABASE_TO_LOWER=TRUE","sa","","outcomes")){
            var service=new OutcomeService(s,null,14,Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"),ZoneOffset.UTC));var eligible=event("one",1,"AWAITING_CREATION",false);
            service.ingestStorage(eligible);service.ingestStorage(eligible);service.ingestStorage(event("two",2,"ACTIVE",true));service.ingestStorage(event("three",3,"EXPIRED",true));
            var rate=service.performance("catalog","2026-09-25T00:00:00Z","DEMO");assertEquals(1,rate.path("E").asInt());assertEquals(1,rate.path("Y").asInt());assertEquals(100,rate.path("acceptanceAmongEligiblePct").asDouble());assertEquals("MATURE",rate.path("maturity").asText());
            assertEquals(0,service.performance("catalog","2026-09-25T00:00:00Z","OBSERVED_REAL").path("E").asInt());
            var comparison=service.compare(obj("sourceMode","DEMO","scope","CATALOG_TERMS_ONLY","predictionKind","SIMULATED_UTILITY","estimatePct",80),rate);
            assertEquals("TARGET_MISMATCH",comparison.path("comparability").asText());assertTrue(comparison.path("gapPercentagePoints").isNull());
        }
    }
}
