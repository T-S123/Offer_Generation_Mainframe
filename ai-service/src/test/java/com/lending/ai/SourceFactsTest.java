package com.lending.ai;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.lending.ai.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/** Operational facts are distinct from demo events, retain corrections and require complete denominator coverage. */
class SourceFactsTest {
    @Test void realLedgerCountsNonSelectorsAndRejectsForgedCorrections(){
        try(var s=new FoundationTest().store()){
            s.create("PUBLICATION","catalog",obj("analyst","tester"));var source=new SourceFacts(s,"customer-system","OBSERVED_REAL",14);
            String person=hash("one"),other=hash("two");var terms=obj("aprPct",10,"amountUsd",10000);
            String eligible="2026-09-01T00:00:00Z",end="2026-09-15T00:00:00Z",now=Instant.now().toString();
            for(String c:List.of(person,other)){
                source.ingest(new SourceFacts.Event("1.0.0","a"+c,"ASSESSMENT","catalog",c,c,1,null,null,eligible,obj("status","ELIGIBLE","profileVersion",1,"policyVersion","p1","admissionId",c)));
                source.ingest(new SourceFacts.Event("1.0.0","e"+c,"ENROLLMENT","catalog",c,c,1,null,null,eligible,obj("familyId","family-"+c,"followUpEndsAt",end,"terms",terms,"sourceVersion",1)));
            }
            var selected=new SourceFacts.Event("1.0.0","selection-one","SELECTION","catalog",person,"choice",1,null,null,"2026-09-03T00:00:00Z",obj("familyId","family-"+person,"kind","ORIGINAL","terms",terms,"sourceVersion",1));
            source.ingest(selected);source.ingest(selected);
            source.ingest(new SourceFacts.Event("1.0.0","watermark","WATERMARK","catalog",null,"catalog",1,null,null,now,obj("through",now,"complete",true,"includesAllAdmissions",true,"entryStartsAt",eligible,"entryEndsAt",end,"followUpDays",14)));
            var outcome=new OutcomeService(s,null,14);var rate=outcome.performance("catalog",Instant.now().toString(),"OBSERVED_REAL");
            assertEquals(2,rate.path("E").asInt());assertEquals(1,rate.path("Y").asInt());assertEquals(50,rate.path("acceptanceAmongEligiblePct").asDouble());assertEquals(100,rate.path("eligibilityPct").asDouble());
            assertEquals("OPERATIONAL_SOURCE_RECONCILED",rate.path("coverage").asText());assertEquals(0,outcome.performance("catalog",Instant.now().toString(),"DEMO").path("E").asInt());
            var match=outcome.compare(obj("scope","OFFER_FAMILY","sourceMode","OBSERVED_REAL","predictionKind","CALIBRATED_SELECTION","estimatePct",80),rate);
            assertEquals(-30,match.path("gapPercentagePoints").asDouble());
            assertThrows(Fault.class,()->source.ingest(new SourceFacts.Event("1.0.0","bad-correction","SELECTION","catalog",other,"choice",2,"selection-one","Attempt to reassign subject","2026-09-03T00:00:00Z",selected.data())));
            assertThrows(Fault.class,()->source.ingest(new SourceFacts.Event("1.0.0","bad-feature","FORECAST_FEATURE","catalog",person,person,1,null,null,eligible,obj("baseProbability",.8,"featureAsOf",now,"product","PERSONAL_LOAN","policyContext","test","scope","OFFER_FAMILY","followUpDays",14))));
        }
    }
}
