/**
 * Real PostgreSQL lifecycle, execution candidates, qualification-bound choices, privacy, transaction
 * failure and upgrade/replay regressions.
 */
package com.lending.offers;
import com.lending.offers.infrastructure.*;
import com.lending.offers.application.*;
import com.lending.offers.domain.Model.*;
import static com.lending.offers.infrastructure.Database.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Instant;
import java.util.*;

/**
 * Real PostgreSQL lifecycle, execution candidates, qualification-bound choices, privacy, transaction
 * failure and upgrade/replay regressions.
 */
@EnabledIfEnvironmentVariable(named="BUREAU_TEST_DATABASE_URL",matches=".+")
class StorageIntegrationTest {
    OfferIntegrationTest f;StorageJournal journal;
    /** Creates isolated test resources and wires the workflow under test. */
    @BeforeEach void setup(){f=new OfferIntegrationTest();f.setup();journal=new StorageJournal(f.db);}
    /** Closes test resources and removes only the temporary artifacts owned by the test. */
    @AfterEach void cleanup(){f.cleanup();}
    /** Verifies that execution candidates wait for personalization and recheck both choices. */
    @Test void executionCandidatesWaitForPersonalizationAndRecheckBothChoices(){try(var storage=new StorageEngine(f.engine,f.source)){f.create();var pending=Json.MAPPER.valueToTree(storage.candidates("customer-1","CAMPAIGN"));assertTrue(pending.path("pending").asBoolean());f.fitted();f.engine.process();f.engine.select("response-1",new Selection("preferred",1,"ORIGINAL"));var ready=Json.MAPPER.valueToTree(storage.candidates("customer-1","CAMPAIGN"));assertFalse(ready.path("pending").asBoolean());assertEquals(1,ready.path("items").size());assertFalse(ready.path("items").get(0).path("offer").path("personalized").isNull());assertTrue(ready.path("items").get(0).path("stored").path("selection").path("available").asBoolean());f.source.unavailable=true;assertThrows(Json.Fault.class,()->storage.candidates("customer-1","CAMPAIGN"));f.source.unavailable=false;f.source.revoked=true;assertTrue(Json.MAPPER.valueToTree(storage.candidates("customer-1","CAMPAIGN")).path("items").isEmpty());}}
    /** Builds the finalized pre-screen match used by the storage test. */
    ObjectNode match(){var row=OfferIntegrationTest.response(1,true);row.put("id","DR-"+"a".repeat(60));row.put("status","AWAITING_BUREAU");row.set("terms",Json.MAPPER.valueToTree(PersonalizationTest.terms(10000,15,100,36)));row.put("displayName","PRIVATE CUSTOMER");return row;}
    /** Verifies that hidden prescreen is idempotent and cannot downgrade bureau or personalized state. */
    @Test void hiddenPrescreenIsIdempotentAndCannotDowngradeBureauOrPersonalizedState(){var match=match();journal.prescreen(match);journal.prescreen(match);var id=match.path("id").asText();assertEquals(1,journal.latest(id).offer().version());assertFalse(journal.latest(id).offer().preScreen().visible());assertFalse(Json.write(journal.latest(id)).contains("PRIVATE"));
        var decision=match.deepCopy();decision.put("status","BUREAU_DECLINED");decision.put("marketingEligible",false);f.store.receive("declined",decision);journal.prescreen(match);assertEquals("BUREAU_DECLINED",journal.latest(id).offer().preScreen().status());assertFalse(journal.latest(id).offer().preScreen().visible());}
    /** Verifies that both categories selections and withdrawal are versioned without private facts. */
    @Test void bothCategoriesSelectionsAndWithdrawalAreVersionedWithoutPrivateFacts(){f.source.response.put("displayName","PRIVATE CUSTOMER");f.source.response.putObject("bureauProfile").put("creditScore",700);f.fitted();f.create();var active=journal.latest("response-1");assertTrue(active.offer().preScreen().visible());assertTrue(active.offer().marketing().visible());assertFalse(active.offer().modelMetrics().isEmpty());assertFalse(Json.write(active).contains("creditScore"));assertFalse(Json.write(active).contains("PRIVATE"));
        f.engine.select("response-1",new Selection("choice-1",1,"PERSONALIZED"));var chosen=journal.latest("response-1");assertTrue(chosen.offer().selection().available());assertTrue(chosen.offer().version()>active.offer().version());long count=(long)journal.health().get("events");f.engine.select("response-1",new Selection("choice-1",1,"PERSONALIZED"));assertEquals(count,journal.health().get("events"));
        f.source.variantRevoked=true;f.engine.current("response-1");var variant=journal.latest("response-1").offer();assertTrue(variant.preScreen().visible());assertFalse(variant.marketing().visible());assertFalse(variant.selection().available());f.source.revoked=true;assertThrows(Json.Fault.class,()->f.engine.current("response-1"));assertFalse(journal.latest("response-1").offer().preScreen().visible());assertTrue(journal.history("response-1",0).size()>=4);}
    /** Verifies that ledger and outbox rollback together and recovery pages have stable cursors. */
    @Test void ledgerAndOutboxRollbackTogetherAndRecoveryPagesHaveStableCursors(){f.create();long before=(long)journal.health().get("events");assertThrows(IllegalStateException.class,()->f.db.tx(c->{StorageJournal.response(c,OfferIntegrationTest.response(2,false));throw new IllegalStateException("simulated transaction failure");}));assertEquals(before,journal.health().get("events"));assertTrue(journal.latest("response-1").offer().preScreen().visible());
        var page=journal.events(0,1);assertEquals(1,page.items().size());assertNotNull(page.nextAfter());var next=journal.events(Long.parseLong(page.nextAfter()),100);assertTrue(next.items().stream().allMatch(e->e.sequence()>page.items().get(0).sequence()));}
    /** Verifies that identical terms after requalification require A new selection. */
    @Test void identicalTermsAfterRequalificationRequireANewSelection(){f.create();f.engine.select("response-1",new Selection("old-choice",1,"ORIGINAL"));assertTrue(journal.latest("response-1").offer().selection().available());f.source.response=OfferIntegrationTest.response(2,true);f.store.receive("new-approval",f.source.response);f.engine.process();var current=journal.latest("response-1").offer();assertTrue(current.preScreen().visible());assertEquals(1,current.selection().sourceVersion());assertFalse(current.selection().available());f.engine.select("response-1",new Selection("new-choice",2,"ORIGINAL"));assertTrue(journal.latest("response-1").offer().selection().available());assertEquals(2,journal.latest("response-1").offer().selection().sourceVersion());}
    /** Verifies that upgrade preserves step five offers terms choices and history and is repeatable. */
    @Test void upgradePreservesStepFiveOffersTermsChoicesAndHistoryAndIsRepeatable(){f.fitted();f.create();f.engine.select("response-1",new Selection("legacy-selection",1,"PERSONALIZED"));var old=f.store.get("response-1");String history=f.db.tx(c->scalar(c,"SELECT count(*) FROM offer_history"));
        f.db.tx(c->{execute(c,"DROP TABLE storage_seen,storage_events,storage_current");execute(c,"DELETE FROM schema_version WHERE version=2");return null;});
        try(var upgraded=new Database(System.getenv("BUREAU_TEST_DATABASE_URL"),f.schema)){var copy=new StorageJournal(upgraded).latest("response-1").offer();assertEquals(old.original().terms(),copy.preScreen().terms());assertEquals(old.personalized().terms(),copy.marketing().terms());assertEquals("legacy-selection",copy.selection().requestId());assertTrue(copy.selection().available());assertEquals(history,upgraded.tx(c->scalar(c,"SELECT count(*) FROM offer_history")));}
        long events=(long)journal.health().get("events");try(var reopened=new Database(System.getenv("BUREAU_TEST_DATABASE_URL"),f.schema)){assertEquals(events,new StorageJournal(reopened).health().get("events"));}}
    /** Verifies that scanner resumes persisted cursor and retains hidden matches. */
    @Test void scannerResumesPersistedCursorAndRetainsHiddenMatches(){var seen=new ArrayList<String>();var stableMatch=match();Source source=new Source(){
        /** Returns the HTTP or source-port response required by the test fixture. */
        public com.fasterxml.jackson.databind.JsonNode get(String path){seen.add(path);return Json.MAPPER.valueToTree(Map.of("items",List.of(stableMatch),"nextAfter","cursor-1"));}
        /** Submits the HTTP or source-port request required by the test fixture. */
        public com.fasterxml.jackson.databind.JsonNode post(String path,Object body){throw new UnsupportedOperationException();}};try(var scanner=new StorageEngine(f.engine,source)){scanner.scan();scanner.scan();}assertTrue(seen.get(1).contains("after=cursor-1"));assertEquals(1L,journal.health().get("events"));}
}
