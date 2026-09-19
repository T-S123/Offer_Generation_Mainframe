/**
 * Independent PostgreSQL copy ownership, monotonic replay, privacy quarantine and HTTP recovery after
 * outages/restarts.
 */
package com.lending.engine;
import com.lending.engine.storage.*;
import com.lending.engine.domain.Model.Problem;
import com.lending.engine.infrastructure.Json;
import static com.lending.engine.bureau.infrastructure.BureauDatabase.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;

/**
 * Independent PostgreSQL copy ownership, monotonic replay, privacy quarantine and HTTP recovery after
 * outages/restarts.
 */
@EnabledIfEnvironmentVariable(named="BUREAU_TEST_DATABASE_URL",matches=".+")
class StorageCopyIntegrationTest {
    OfferCopyStore customer,warehouse;String url=System.getenv("BUREAU_TEST_DATABASE_URL");
    /** Creates isolated test resources and wires the workflow under test. */
    @BeforeEach void setup(){String id=UUID.randomUUID().toString().replace("-","");customer=new OfferCopyStore(url,"customer_copy_test_"+id);warehouse=new OfferCopyStore(url,"warehouse_copy_test_"+id);}
    /** Closes test resources and removes only the temporary artifacts owned by the test. */
    @AfterEach void cleanup(){for(var copy:List.of(customer,warehouse)){copy.tx(c->{execute(c,"DROP SCHEMA "+copy.schema+" CASCADE");return null;});copy.close();}}
    /** Builds a versioned offer-storage event for replay and quarantine tests. */
    static ObjectNode event(int version){var e=Json.MAPPER.createObjectNode();e.put("eventId","event-"+version);e.put("schemaVersion",1);e.put("type","OFFER_STORAGE_UPDATED");e.put("occurredAt",Instant.parse("2026-09-18T00:00:00Z").toString());e.put("sequence",version);var o=e.putObject("offer");o.put("id","offer-1");o.put("version",version);o.put("customerId","customer-1");o.put("product","PERSONAL_LOAN");o.put("currency","USD");var q=o.putObject("qualification");for(String k:List.of("runId","riskId","qualificationId","campaignId","catalogOfferId","bureauDecisionId","underwritingDecisionId","eligibilityPolicyVersion"))q.putNull(k);for(String k:List.of("responseVersion","campaignVersion","catalogOfferVersion"))q.put(k,0);var p=o.putObject("preScreen");p.put("category","PRE_SCREEN");p.put("status","AWAITING_BUREAU");p.put("visible",false);for(String k:List.of("terms","metrics","variantQualificationId","validUntil"))p.putNull(k);for(String k:List.of("marketing","selection","modelId","policyVersion","cohort"))o.putNull(k);o.putObject("modelMetrics");o.putObject("deltas");return e;}
    /** Verifies that out of order and duplicate events retain history but never downgrade current copy. */
    @Test void outOfOrderAndDuplicateEventsRetainHistoryButNeverDowngradeCurrentCopy(){var first=event(1);var second=event(2);customer.receive(second);customer.receive(first);customer.receive(second);assertEquals(2,customer.get("offer-1").path("offer").path("version").asInt());assertEquals(2,customer.history("offer-1",0).size());assertEquals(0L,warehouse.health().get("families"));warehouse.receive(first);assertEquals(1,warehouse.get("offer-1").path("offer").path("version").asInt());var conflict=second.deepCopy();conflict.put("occurredAt",Instant.now().toString());assertEquals(409,assertThrows(Problem.class,()->customer.receive(conflict)).status);}
    /** Verifies that malformed private and conflicting kafka records are durably quarantined. */
    @Test void malformedPrivateAndConflictingKafkaRecordsAreDurablyQuarantined(){var source=new StorageClient(1,"unused");var loader=new OfferCopyLoader(customer,source,"127.0.0.1:9092","unused");var privateEvent=event(1);((ObjectNode)privateEvent.path("offer")).put("displayName","PRIVATE CUSTOMER");loader.accept("offer-1",privateEvent.toString(),"topic:0:0");loader.accept("offer-1",null,"topic:0:1");loader.accept("wrong",event(1).toString(),"topic:0:2");loader.accept("offer-1",event(1).toString(),"topic:0:3");assertEquals(3L,customer.health().get("rejected"));assertEquals(1L,customer.health().get("historyEvents"));assertFalse(customer.tx(c->scalar(c,"SELECT coalesce(string_agg(fingerprint||reason,''),'') FROM rejected_events")).contains("PRIVATE"));assertThrows(Problem.class,()->StorageContract.validate(null));}
    /** Verifies that cursor and history survive restart and cursor cannot move backwards. */
    @Test void cursorAndHistorySurviveRestartAndCursorCannotMoveBackwards(){customer.receive(event(1));customer.cursor(1);customer.cursor(0);try(var reopened=new OfferCopyStore(url,customer.schema)){assertEquals(1,reopened.cursor());assertEquals(1,reopened.history("offer-1",0).size());assertEquals(1,((List<?>)reopened.page("customer-1",null,1).get("items")).size());assertNotNull(reopened.page("customer-1",null,1).get("nextAfter"));}}
    /** Verifies that HTTP recovery catches missed kafka and only checkpoints complete pages. */
    @Test void httpRecoveryCatchesMissedKafkaAndOnlyCheckpointsCompletePages()throws Exception{var broken=new java.util.concurrent.atomic.AtomicBoolean(true);var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);server.createContext("/api/v1/storage/events",x->{String query=x.getRequestURI().getQuery();var items=query.contains("after=2")?List.of():query.contains("after=1")?List.of(event(2)):List.of(event(1),event(2));var page=Json.MAPPER.valueToTree(Map.of("items",items));if(broken.get())((ObjectNode)page.path("items").get(1)).put("schemaVersion",99);byte[] body=Json.write(page).getBytes(java.nio.charset.StandardCharsets.UTF_8);x.sendResponseHeaders(200,body.length);x.getResponseBody().write(body);x.close();});server.start();
        try{var client=new StorageClient(server.getAddress().getPort(),"token");var loader=new OfferCopyLoader(customer,client,"127.0.0.1:9092","unused");assertThrows(Problem.class,loader::catchUp);assertEquals(0,customer.cursor());assertEquals(1L,customer.health().get("historyEvents"));broken.set(false);loader.catchUp();assertEquals(2,customer.cursor());assertEquals(2L,customer.health().get("historyEvents"));var restart=new OfferCopyLoader(customer,client,"127.0.0.1:9092","unused");restart.catchUp();assertEquals(2L,customer.health().get("historyEvents"));var independent=new OfferCopyLoader(warehouse,client,"127.0.0.1:9092","unused");independent.catchUp();assertEquals(2,warehouse.cursor());}finally{server.stop(0);}}
}
