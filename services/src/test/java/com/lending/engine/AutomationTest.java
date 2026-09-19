/**
 * Automatic qualification reuses existing rules, fits legacy identifiers, and replaces only the same
 * customer's automatic reservation.
 */
package com.lending.engine;
import com.lending.engine.application.CustomerEngine;
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
 * Automatic qualification reuses existing rules, fits legacy identifiers, and replaces only the same
 * customer's automatic reservation.
 */
class AutomationTest {
    @TempDir Path temp;
    /** Verifies that auto finalization retries and changed profiles keep stable isolated history. */
    @Test void autoFinalizationRetriesAndChangedProfilesKeepStableIsolatedHistory(){var clock=Clock.fixed(ExecutionTest.NOW,ZoneOffset.UTC);try(var store=new SqlStore("jdbc:h2:mem:"+UUID.randomUUID());var customers=new CustomerEngine(store,new CobolUnderwriter(Path.of("../build/liuwbatch"),temp),clock)){var marketing=new MarketingEngine(store,new CobolMarketingQualifier(Path.of("../build/limkbatch"),temp),clock);var c=customers.create(ExecutionTest.opted());String token=customers.pipeline(c.id()).token();var run=marketing.automatic(c.id(),token);assertEquals("FINALIZED",run.status());assertTrue(run.request().requestId().length()<=60);assertTrue(run.qualifiedOffers()>0);assertEquals(run,marketing.automatic(c.id(),token));assertEquals(1,marketing.runs(0,20).total());assertTrue(marketing.results(run.id(),0,200,null).items().stream().allMatch(q->q.customerId().equals(c.id())));customers.update(c.id(),new Update(1,EngineTest.change(c.current().data(),"preferredChannels",List.of("SMS"))));assertThrows(Problem.class,()->marketing.automatic(c.id(),token));var next=marketing.automatic(c.id(),customers.pipeline(c.id()).token());assertNotEquals(run.id(),next.id());assertEquals("CANCELLED",marketing.run(run.id()).status());assertTrue(marketing.reservations(run.id()).stream().allMatch(r->r.releasedAt()!=null));assertTrue(next.qualifiedOffers()>0);var denied=customers.create(EngineTest.change(EngineTest.good(),"externalReference","denied"));var stopped=marketing.automatic(denied.id(),customers.pipeline(denied.id()).token());assertEquals(0,stopped.qualifiedOffers());assertEquals("FINALIZED",marketing.run(next.id()).status());}}
}
