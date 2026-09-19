/**
 * PostgreSQL/HTTP tests cover training, recovery, withdrawal and customer confirmation bound to current
 * qualification and reviewed terms.
 */
package com.lending.offers;
import com.lending.offers.Json;
import com.lending.offers.domain.*;
import com.lending.offers.application.*;
import com.lending.offers.infrastructure.*;
import com.lending.offers.api.*;
import static com.lending.offers.domain.Model.*;
import static com.lending.offers.infrastructure.Database.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.time.*;
import java.net.*;
import java.net.http.*;

/**
 * PostgreSQL/HTTP tests cover training, recovery, withdrawal and customer confirmation bound to current
 * qualification and reviewed terms.
 */
@EnabledIfEnvironmentVariable(named="BUREAU_TEST_DATABASE_URL",matches=".+")
class OfferIntegrationTest {
    String schema="offers_test_"+UUID.randomUUID().toString().replace("-","");Database db;OfferStore store;OfferEngine engine;FakeSource source;
    /** Creates isolated test resources and wires the workflow under test. */
    @BeforeEach void setup(){db=new Database(System.getenv("BUREAU_TEST_DATABASE_URL"),schema);store=new OfferStore(db);source=new FakeSource();engine=new OfferEngine(store,source);}
    /** Closes test resources and removes only the temporary artifacts owned by the test. */
    @AfterEach void cleanup(){if(engine!=null)engine.close();if(db!=null){db.tx(c->{execute(c,"DROP SCHEMA "+schema+" CASCADE");return null;});db.close();}}
    /** Builds a versioned qualification-response fixture. */
    static ObjectNode response(int version,boolean eligible){return (ObjectNode)Json.MAPPER.valueToTree(Map.of("id","response-1","version",version,"marketingEligible",eligible,"customerId","customer-1","validUntil",Instant.now().plusSeconds(3600).toString(),"source",Map.of("riskId","risk-1"),"qualification",Map.of("product","PERSONAL_LOAN","offerId","DEMO-PL-1","offerVersion",1,"campaignId","CAMPAIGN"),"qualifiedOffer",Map.of("data",Map.of("minimumAmountUsd",5000,"maximumAmountUsd",20000))));}
    /** Groups fake source behavior for offer integration test operations. */
    static final class FakeSource implements Source {
        ObjectNode response=response(1,true);Map<String,JsonNode> approvals=new HashMap<>();boolean revoked=false,unavailable=false,decline=false,variantRevoked=false;
        /** Returns the HTTP or source-port response required by the test fixture. */
        public JsonNode get(String route){if(unavailable)throw new Json.Fault(503,"offline");if(route.startsWith("contexts/")){if(revoked)throw new Json.Fault(409,"revoked");return Json.MAPPER.valueToTree(Map.of("response",response,"features",PersonalizationTest.population(1).get(0),"original",PersonalizationTest.terms(10000,15,100,36),"policyVersion","MOF-test","policy",Map.of("minimumAprPct",Map.of("PERSONAL_LOAN",6),"maximumAprReductionPct",2,"maximumAmountChangePct",10,"minimumTermMonths",12,"maximumTermMonths",84,"maximumTermChangeMonths",12)));}if(route.startsWith("approvals/")){if(revoked||variantRevoked)throw new Json.Fault(409,"revoked");return approvals.get(route.substring(10));}throw new UnsupportedOperationException(route);}
        /** Submits the HTTP or source-port request required by the test fixture. */
        public JsonNode post(String route,Object value){var request=Json.MAPPER.valueToTree(value);var approval=((ObjectNode)request).deepCopy();approval.put("approved",!decline);approval.put("validUntil",response.path("validUntil").asText());approvals.put(request.path("requestId").asText(),approval);return approval;}
    }
    /** Returns a fitted cohort model for the personalization test fixture. */
    void fitted(){String id=store.train(new Training("batch",42));var job=store.claimTraining();assertEquals(id,job.id());store.trained(job,Cohorts.train(id,job.request(),PersonalizationTest.population(120)));}
    /** Creates the eligible customer or offer family needed by the test scenario. */
    void create(){store.receive("e1",source.response);engine.process();}
    /**
     * Verifies that presented selection rejects wrong customer stale terms and versions and replays
     * safely.
     */
    @Test void presentedSelectionRejectsWrongCustomerStaleTermsAndVersionsAndReplaysSafely()throws Exception{
        fitted();create();var offer=engine.current("response-1");var terms=offer.personalized().terms();var request=new PresentedSelection("confirm-1",1,"PERSONALIZED",terms);
        assertEquals(404,assertThrows(Json.Fault.class,()->engine.selectPresented("another-customer",offer.id(),request)).status);
        assertEquals(409,assertThrows(Json.Fault.class,()->engine.selectPresented("customer-1",offer.id(),new PresentedSelection("stale",2,"PERSONALIZED",terms))).status);
        assertEquals(409,assertThrows(Json.Fault.class,()->engine.selectPresented("customer-1",offer.id(),new PresentedSelection("wrong-terms",1,"PERSONALIZED",offer.original().terms()))).status);
        assertEquals(0L,store.health().get("selections"));
        try(var api=new OfferServer(engine,null,0,"t".repeat(40))){api.start();var url=URI.create("http://127.0.0.1:"+api.port()+"/api/v1/customers/customer-1/offers/response-1/selections");var http=HttpClient.newHttpClient();var post=HttpRequest.newBuilder(url).header("Authorization","Bearer "+"t".repeat(40)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(request))).build();var first=http.send(post,HttpResponse.BodyHandlers.ofString());assertEquals(200,first.statusCode(),first.body());assertEquals(terms,Json.MAPPER.convertValue(Json.tree(first.body()).path("terms"),Terms.class));assertEquals(first.body(),http.send(post,HttpResponse.BodyHandlers.ofString()).body());}
        assertEquals(1L,store.health().get("selections"));
        assertEquals(409,assertThrows(Json.Fault.class,()->engine.selectPresented("customer-1",offer.id(),new PresentedSelection("confirm-1",1,"ORIGINAL",offer.original().terms()))).status);
        source.unavailable=true;assertEquals(503,assertThrows(Json.Fault.class,()->engine.selectPresented("customer-1",offer.id(),request)).status);source.unavailable=false;
        source.variantRevoked=true;assertEquals(409,assertThrows(Json.Fault.class,()->engine.selectPresented("customer-1",offer.id(),request)).status);
        assertNotNull(engine.selectPresented("customer-1",offer.id(),new PresentedSelection("original-2",1,"ORIGINAL",offer.original().terms())));
        source.revoked=true;assertEquals(409,assertThrows(Json.Fault.class,()->engine.selectPresented("customer-1",offer.id(),new PresentedSelection("revoked",1,"ORIGINAL",offer.original().terms()))).status);
    }
    /** Verifies that automatic training is idempotent and bootstrap has no customer contacts. */
    @Test void automaticTrainingIsIdempotentAndBootstrapHasNoCustomerContacts(){var request=new Training(BootstrapPopulation.BATCH,73107);store.autoTrain(request);store.autoTrain(request);assertEquals("1",db.tx(c->scalar(c,"SELECT count(*) FROM training_jobs")));engine.trainOne();assertNotNull(store.model());assertEquals(1000,store.model().metrics().get("trainingCount")+store.model().metrics().get("validationCount")+store.model().metrics().get("testCount"));assertEquals(0d,store.model().metrics().get("observedAcceptanceLabels"));new OfferStore(db).autoTrain(request);assertEquals("1",db.tx(c->scalar(c,"SELECT count(*) FROM training_jobs")));assertEquals(Json.write(BootstrapPopulation.generate()),Json.write(BootstrapPopulation.generate()));for(var row:BootstrapPopulation.generate())for(String privateField:List.of("displayName","email","phone","creditScore","customerText"))assertFalse(row.has(privateField));}
    /** Verifies that duplicate out of order and conflicting messages cannot reactivate. */
    @Test void duplicateOutOfOrderAndConflictingMessagesCannotReactivate(){create();assertEquals("ACTIVE",store.get("response-1").status());store.receive("e1",source.response);assertEquals(1L,store.health().get("inbox"));var revoked=response(2,false);store.receive("e2",revoked);store.receive("old",source.response);assertEquals("REVOKED",store.get("response-1").status());assertThrows(Json.Fault.class,()->store.receive("e2",source.response));engine.process();assertEquals("REVOKED",store.get("response-1").status());}
    /** Verifies that older worker cannot overwrite revocation or steal new lease. */
    @Test void olderWorkerCannotOverwriteRevocationOrStealNewLease(){store.receive("e1",source.response);var old=store.claim();store.receive("e2",response(2,false));var newer=store.claim();store.finish(old,null,"DONE");assertEquals("2",db.tx(c->scalar(c,"SELECT version FROM offer_work")));assertEquals(newer.token(),db.tx(c->scalar(c,"SELECT lease_token FROM offer_work")));}
    /** Verifies that training unblocks variants and both choices are auditable. */
    @Test void trainingUnblocksVariantsAndBothChoicesAreAuditable(){create();assertEquals("WAITING_FOR_MODEL",store.get("response-1").personalizationStatus());assertNull(store.get("response-1").personalized());fitted();engine.process();var offer=engine.current("response-1");assertNotNull(offer.personalized());assertTrue(offer.deltas().get("fitScore")>0);assertNotEquals(offer.original().terms(),offer.personalized().terms());var request=new Selection("s1",1,"PERSONALIZED");assertEquals(Json.write(engine.select(offer.id(),request)),Json.write(engine.select(offer.id(),request)));assertThrows(Json.Fault.class,()->engine.select(offer.id(),new Selection("s1",1,"ORIGINAL")));engine.select(offer.id(),new Selection("s2",1,"ORIGINAL"));assertEquals(2L,store.health().get("selections"));assertFalse(((List<?>)store.history(offer.id(),0)).isEmpty());source.revoked=true;assertTrue(engine.customer("customer-1",null,20).items().isEmpty());assertThrows(Json.Fault.class,()->engine.select(offer.id(),new Selection("s3",1,"ORIGINAL")));}
    /** Verifies that declined variants never activate and read outages fail closed. */
    @Test void declinedVariantsNeverActivateAndReadOutagesFailClosed(){source.decline=true;fitted();create();assertNull(store.get("response-1").personalized());assertEquals("NO_QUALIFIED_IMPROVEMENT",store.get("response-1").personalizationStatus());source.unavailable=true;assertEquals(503,assertThrows(Json.Fault.class,()->engine.customer("customer-1",null,20)).status);assertEquals("ACTIVE",store.get("response-1").status());}
    /** Verifies that malformed kafka is quarantined and offsets can commit after durable receive. */
    @Test void malformedKafkaIsQuarantinedAndOffsetsCanCommitAfterDurableReceive(){try(var consumer=new QualificationConsumer(store,"127.0.0.1:9092","test","test")){consumer.accept("risk-1","not-json","position-1");consumer.accept("wrong",Json.write(Map.of("eventId","e1","type","QUALIFICATION_UPDATED","schemaVersion",1,"response",source.response)),"position-2");assertEquals(2L,store.health().get("rejected_events"));consumer.accept("risk-1",Json.write(Map.of("eventId","e1","type","QUALIFICATION_UPDATED","schemaVersion",1,"response",source.response)),"position-3");assertEquals(1L,store.health().get("inbox"));}}
    /** Verifies that separate HTTP API enforces token and returns financial comparisons. */
    @Test void separateHttpApiEnforcesTokenAndReturnsFinancialComparisons()throws Exception{fitted();create();try(var api=new OfferServer(engine,null,0,"t".repeat(40))){api.start();var url=URI.create("http://127.0.0.1:"+api.port()+"/api/v1/customers/customer-1/offers");var client=HttpClient.newHttpClient();assertEquals(401,client.send(HttpRequest.newBuilder(url).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());var result=client.send(HttpRequest.newBuilder(url).header("Authorization","Bearer "+"t".repeat(40)).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,result.statusCode());assertFalse(Json.tree(result.body()).path("items").get(0).path("personalized").isNull());}}
    /** Verifies that expired variant retains original but cannot replay personalized selection. */
    @Test void expiredVariantRetainsOriginalButCannotReplayPersonalizedSelection(){fitted();create();var request=new Selection("selected",1,"PERSONALIZED");engine.select("response-1",request);source.variantRevoked=true;var current=engine.current("response-1");assertEquals("ACTIVE",current.status());assertNull(current.personalized());assertEquals("VARIANT_WITHDRAWN",current.personalizationStatus());assertThrows(Json.Fault.class,()->engine.select("response-1",request));assertNotNull(engine.select("response-1",new Selection("original",1,"ORIGINAL")));}
    /** Verifies that expired lease recovers after restart and metrics match persisted comparisons. */
    @Test void expiredLeaseRecoversAfterRestartAndMetricsMatchPersistedComparisons(){store.receive("e1",source.response);var abandoned=store.claim();db.tx(c->{execute(c,"UPDATE offer_work SET lease_until=now()-interval '1 second'");return null;});var recovery=new OfferStore(db);var next=recovery.claim();assertNotEquals(abandoned.token(),next.token());recovery.retry(next,"recover");db.tx(c->{execute(c,"UPDATE offer_work SET due=now()");return null;});fitted();engine.process();var offer=engine.current("response-1");var metric=Json.MAPPER.valueToTree(store.metrics()).path("groups").get(0);assertEquals(1,metric.path("personalizedFamilies").asInt());assertEquals(offer.deltas().get("fitScore"),metric.path("meanFitUplift").asDouble(),.00001);var page=engine.customer("customer-1",null,1);assertNotNull(page.nextAfter());assertTrue(engine.customer("customer-1",page.nextAfter(),1).items().isEmpty());try(var reopened=new Database(System.getenv("BUREAU_TEST_DATABASE_URL"),schema)){assertEquals(offer,new OfferStore(reopened).get(offer.id()));}}
}
