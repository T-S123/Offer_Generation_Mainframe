package com.lending.ai;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.lending.ai.Json.*;

/** Counts original eligibility and historical valid selections across duplicate and expiry events. */
class OutcomeServiceTest {
    com.fasterxml.jackson.databind.JsonNode event(String id,int version,String state,boolean selected){
        var terms=obj("amountUsd",10000,"aprPct",10,"annualFeeUsd",0,"termMonths",36);
        return obj("eventId",id,"sequence",version,"occurredAt",version==1?"2026-09-01T00:00:00Z":"2026-09-03T00:00:00Z",
            "offer",obj("id","family","customerId","customer","qualification",obj("catalogOfferId","catalog","catalogOfferVersion",1,"responseVersion",version),
                "preScreen",obj("status",state,"validUntil","2026-09-20T00:00:00Z","terms",terms),
                "selection",selected?obj("requestId","selection","selectedAt","2026-09-02T00:00:00Z","kind","ORIGINAL","terms",terms,"available",false):null));
    }
    @Test void expiryAndReplayDoNotChangeHistoricalNumeratorOrDenominator(){
        try(var s=new Store("jdbc:h2:mem:"+UUID.randomUUID()+";DATABASE_TO_LOWER=TRUE","sa","","outcomes")){
            var service=new OutcomeService(s,null,14);var eligible=event("one",1,"AWAITING_CREATION",false);
            service.ingestStorage(eligible);service.ingestStorage(eligible);service.ingestStorage(event("two",2,"ACTIVE",true));service.ingestStorage(event("three",3,"EXPIRED",true));
            var rate=service.performance("catalog","2026-09-25T00:00:00Z","DEMO");assertEquals(1,rate.path("E").asInt());assertEquals(1,rate.path("Y").asInt());assertEquals(100,rate.path("acceptanceAmongEligiblePct").asDouble());assertEquals("MATURE",rate.path("maturity").asText());
            assertEquals(0,service.performance("catalog","2026-09-25T00:00:00Z","OBSERVED_REAL").path("E").asInt());
            var comparison=service.compare(obj("sourceMode","DEMO","scope","CATALOG_TERMS_ONLY","predictionKind","SIMULATED_UTILITY","estimatePct",80),rate);
            assertEquals("TARGET_MISMATCH",comparison.path("comparability").asText());assertTrue(comparison.path("gapPercentagePoints").isNull());
        }
    }
}
