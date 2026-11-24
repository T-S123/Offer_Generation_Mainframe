/**
 * Automatic qualification keeps isolated reservation history and admits bounded Presenter cohorts.
 */
package com.lending.engine;
import com.lending.engine.application.CustomerEngine;
import com.lending.engine.application.CustomerPreferences;
import com.lending.engine.application.Generator;
import com.lending.engine.domain.Model.*;
import com.lending.engine.infrastructure.*;
import com.lending.engine.marketing.application.MarketingEngine;
import com.lending.engine.marketing.infrastructure.CobolMarketingQualifier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Automatic qualification keeps isolated reservation history and admits bounded Presenter cohorts.
 */
class AutomationTest {
    @TempDir Path temp;
    /** Verifies that the worker backfills saved profiles and replaces a label after an edit. */
    @Test void preferenceWorkerClassifiesSavedAndUpdatedProfiles() throws Exception {
        var clock=Clock.systemUTC();
        try(var store=new SqlStore("jdbc:h2:mem:"+UUID.randomUUID());
            var customers=new CustomerEngine(store,new CobolUnderwriter(Path.of("../build/liuwbatch"),temp),clock)){
            var data=Generator.generate(1,123).get(0);
            var saved=customers.create(data);
            var modeled=CustomerPreferences.classify(data);
            assertNotEquals(CustomerPreferences.Label.INSUFFICIENT_DATA,modeled);
            try(var worker=new CustomerPreferences(store)){
                awaitPreference(store,saved.id(),modeled);
                var updated=EngineTest.change(data,"creditUtilizationPct",null);
                customers.update(saved.id(),new Update(1,updated));
                awaitPreference(store,saved.id(),CustomerPreferences.Label.INSUFFICIENT_DATA);
            }
        }
    }

    private static void awaitPreference(SqlStore store,String id,CustomerPreferences.Label label) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        do {
            try {
                var profiles=store.experimentCustomers(Set.of(id),label,LocalDate.now(ZoneOffset.UTC));
                if(profiles.size()==1)return;
            } catch(Problem pending){assertEquals(409,pending.status);}
            Thread.sleep(30);
        } while(System.nanoTime()<deadline);
        fail("The preference worker did not assign "+label);
    }
    /** Generates all 10,000 Presenter profiles with COBOL and checks that retries reuse their batch. */
    @Test void presenterCohortIsBoundedAndReusesItsRequest(){
        try(var store=new SqlStore("jdbc:h2:mem:"+UUID.randomUUID())){
            Job admitted;
            try(var customers=new CustomerEngine(store,new CobolUnderwriter(Path.of("../build/liuwbatch"),temp),Clock.systemUTC())){
                var request=UUID.randomUUID().toString();
                admitted=customers.simulateDemo(request);
                assertEquals(10_000,admitted.count());
                assertEquals(admitted.id(),customers.simulateDemo(request).id());
                assertEquals(1,customers.jobs().size());
            }
            var completed=store.read(s->s.job(admitted.id()));
            assertEquals("COMPLETED",completed.status(),completed.error());
            var page=store.read(s->s.customers(admitted.id(),9_800,200));
            assertEquals(10_000,page.total());
            assertEquals(200,page.items().size());
            assertTrue(page.items().stream().allMatch(c->admitted.id().equals(c.batchId())));
        }
    }

    /** Retries a failed legacy cohort with its original size and creation time. */
    @Test void failedLegacyPresenterCohortKeepsItsSize(){
        try(var store=new SqlStore("jdbc:h2:mem:"+UUID.randomUUID());
            var customers=new CustomerEngine(store,new CobolUnderwriter(Path.of("../build/liuwbatch"),temp),Clock.systemUTC())){
            var request=UUID.randomUUID();
            var id="DEMO-"+request.toString().replace("-","");
            var createdAt=ExecutionTest.NOW.toString();
            store.write(s->{s.saveJob(new Job(id,"FAILED",100,42L,createdAt,createdAt,Map.of(),"Interrupted"));return null;});
            var retry=customers.simulateDemo(request.toString());
            assertEquals(id,retry.id());
            assertEquals(100,retry.count());
            assertEquals(42L,retry.seed());
            assertEquals(createdAt,retry.createdAt());
        }
    }
    /** Verifies that auto finalization retries and changed profiles keep stable isolated history. */
    @Test void autoFinalizationRetriesAndChangedProfilesKeepStableIsolatedHistory(){var clock=Clock.fixed(ExecutionTest.NOW,ZoneOffset.UTC);try(var store=new SqlStore("jdbc:h2:mem:"+UUID.randomUUID());var customers=new CustomerEngine(store,new CobolUnderwriter(Path.of("../build/liuwbatch"),temp),clock)){var marketing=new MarketingEngine(store,new CobolMarketingQualifier(Path.of("../build/limkbatch"),temp),clock);var c=customers.create(ExecutionTest.opted());String token=customers.pipeline(c.id()).token();var run=marketing.automatic(c.id(),token);assertEquals("FINALIZED",run.status());assertTrue(run.request().requestId().length()<=60);assertTrue(run.qualifiedOffers()>0);assertEquals(run,marketing.automatic(c.id(),token));assertEquals(1,marketing.runs(0,20).total());assertTrue(marketing.results(run.id(),0,200,null).items().stream().allMatch(q->q.customerId().equals(c.id())));customers.update(c.id(),new Update(1,EngineTest.change(c.current().data(),"preferredChannels",List.of("SMS"))));assertThrows(Problem.class,()->marketing.automatic(c.id(),token));var next=marketing.automatic(c.id(),customers.pipeline(c.id()).token());assertNotEquals(run.id(),next.id());assertEquals("CANCELLED",marketing.run(run.id()).status());assertTrue(marketing.reservations(run.id()).stream().allMatch(r->r.releasedAt()!=null));assertTrue(next.qualifiedOffers()>0);var denied=customers.create(EngineTest.change(EngineTest.good(),"externalReference","denied"));var stopped=marketing.automatic(denied.id(),customers.pipeline(denied.id()).token());assertEquals(0,stopped.qualifiedOffers());assertEquals("FINALIZED",marketing.run(next.id()).status());}}
}
