/**
 * PostgreSQL export allocations, immutable revisions, rollback and restart recovery remain independent of
 * Step 2 reservations.
 */
package com.lending.engine;
import com.lending.engine.execution.*;
import com.lending.engine.execution.Execution.*;
import com.lending.engine.infrastructure.Json;
import static com.lending.engine.bureau.infrastructure.BureauDatabase.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * PostgreSQL export allocations, immutable revisions, rollback and restart recovery remain independent of
 * Step 2 reservations.
 */
@EnabledIfEnvironmentVariable(named="BUREAU_TEST_DATABASE_URL",matches=".+")
class ExecutionStoreTest {
    @TempDir Path temp;String schema="execution_test_"+UUID.randomUUID().toString().replace("-","");ExecutionStore store;
    /** Creates isolated test resources and wires the workflow under test. */
    @BeforeEach void setup(){store=new ExecutionStore(System.getenv("BUREAU_TEST_DATABASE_URL"),schema);}
    /** Closes test resources and removes only the temporary artifacts owned by the test. */
    @AfterEach void cleanup(){store.tx(c->{execute(c,"DROP SCHEMA "+schema+" CASCADE");return null;});store.close();}
    /** Verifies that recovery page admission and cursor commit atomically. */
    @Test void recoveryPageAdmissionAndCursorCommitAtomically(){var first=StorageCopyIntegrationTest.event(1);((com.fasterxml.jackson.databind.node.ObjectNode)first.path("offer").path("qualification")).put("campaignId","DEMO-PL");var second=first.deepCopy();second.put("sequence",2);second.put("eventId","event-2");second.put("schemaVersion",99);var invalid=Json.MAPPER.valueToTree(Map.of("items",List.of(first,second)));assertThrows(com.lending.engine.domain.Model.Problem.class,()->store.admit(invalid,0));assertEquals(0,store.cursor());assertTrue(store.due().isEmpty());second.put("schemaVersion",1);store.admit(Json.MAPPER.valueToTree(Map.of("items",List.of(first,second))),0);assertEquals(2,store.cursor());assertEquals(1,store.due().size());store.admit(Json.MAPPER.valueToTree(Map.of("items",List.of(first,second))),0);assertEquals(2,store.cursor());assertEquals(1,store.due().size());assertThrows(com.lending.engine.domain.Model.Problem.class,()->store.admit(Json.MAPPER.valueToTree(Map.of("items",List.of(second,first))),0));assertEquals(2,store.cursor());}
    /** Verifies that revisions retries and restart do not double count exports. */
    @Test void revisionsRetriesAndRestartDoNotDoubleCountExports()throws Exception{var p=ExecutionTest.draft(UUID.randomUUID().toString(),"DEMO-PL","dispatch");var policy=new Policy("test",86400,30,3);var one=store.prepare(p,policy,ExecutionTest.NOW,h->{assertFalse(h.sameDispatch());assertEquals(0,h.windowCount());return "APPROVED";});assertEquals(1,one.version());assertFalse(store.filesReady(one));assertThrows(IllegalStateException.class,()->store.publish(one,v->{throw new java.io.IOException("disk full");}));assertFalse(store.filesReady(one));var files=new OutboundFiles(temp);store.publish(one,files::write);assertTrue(store.filesReady(one));assertEquals(1,store.prepare(p,policy,ExecutionTest.NOW,h->{assertTrue(h.sameDispatch());assertEquals(1,h.windowCount());return "APPROVED";}).version());store.close();store=new ExecutionStore(System.getenv("BUREAU_TEST_DATABASE_URL"),schema);assertTrue(store.filesReady(store.get(p.id())));var node=(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.valueToTree(p);node.put("selectionId","new-choice");var revised=store.prepare(Json.MAPPER.convertValue(node,Execution.Package.class),policy,ExecutionTest.NOW,h->h.sameDispatch()?"APPROVED":"WRONG");assertEquals(2,revised.version());assertEquals("1",store.tx(c->scalar(c,"SELECT count(*) FROM execution_dispatches")));store.publish(one,v->fail("Stale publisher must be fenced"));assertFalse(store.filesReady(revised));store.publish(revised,files::write);var withdrawn=store.invalidate(p.id(),"GLOBAL_SUPPRESSION");store.publish(withdrawn,files::write);assertEquals(3,withdrawn.version());assertEquals("1",store.tx(c->scalar(c,"SELECT count(*) FROM execution_dispatches")));assertEquals(3,Json.MAPPER.valueToTree(store.history(p.id(),0)).size());var other=ExecutionTest.draft(p.customerId(),"DEMO-CC","other-dispatch");assertNull(store.prepare(other,policy,ExecutionTest.NOW,h->{assertEquals(0,h.secondsSinceLast());assertEquals(1,h.windowCount());return "EXECUTION_COOLDOWN";}));assertEquals("1",store.tx(c->scalar(c,"SELECT count(*) FROM execution_packages")));}
}
