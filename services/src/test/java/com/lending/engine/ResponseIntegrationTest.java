/**
 * Business-operations regression: Isolated PostgreSQL, COBOL, HTTP, Kafka and 3270 response tests,
 * including preservation of legacy data through the additive offer-variant migration.
 */
package com.lending.engine;

import com.lending.engine.api.ApiServer;
import com.lending.engine.application.CustomerEngine;
import com.lending.engine.bureau.application.*;
import com.lending.engine.bureau.api.BureauApi;
import com.lending.engine.bureau.domain.Bureau.*;
import com.lending.engine.bureau.infrastructure.*;
import static com.lending.engine.bureau.infrastructure.BureauDatabase.*;
import com.lending.engine.credit.*;
import com.lending.engine.domain.Model.*;
import com.lending.engine.infrastructure.*;
import com.lending.engine.marketing.application.MarketingEngine;
import com.lending.engine.marketing.domain.Marketing.*;
import com.lending.engine.marketing.infrastructure.CobolMarketingQualifier;
import com.lending.engine.response.application.ResponseEngine;
import com.lending.engine.response.api.ResponseApi;
import com.lending.engine.response.infrastructure.ResponseRepository;
import com.lending.engine.response.domain.DecisionResponse.Response;
import com.lending.engine.terminal.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * Business-operations regression: Isolated PostgreSQL, COBOL, HTTP, Kafka and 3270 response tests,
 * including preservation of legacy data through the additive offer-variant migration.
 */
@EnabledIfEnvironmentVariable(named="BUREAU_TEST_DATABASE_URL",matches=".+")
class ResponseIntegrationTest {
    @TempDir Path temp;
    String schema="response_test_"+UUID.randomUUID().toString().replace("-","");
    /** Groups mutable clock behavior for response integration test operations. */
    static final class MutableClock extends Clock {
        volatile Instant now=CreditPolicyTest.NOW;
        /** Returns the time zone configured for the controlled test clock. */
        public ZoneId getZone(){return ZoneOffset.UTC;}
        /** Returns a clock using the requested time zone for time-dependent tests. */
        public Clock withZone(ZoneId zone){return Clock.fixed(now,zone);}
        /** Returns the controlled instant used by time-dependent tests. */
        public Instant instant(){return now;}
    }
    MutableClock clock=new MutableClock();SqlStore store;CustomerEngine customers;MarketingEngine marketing;
    BureauDatabase db;BureauQueue queue;MarketingSource source;CobolCreditPolicy policy;CreditEngine credit;
    CreditServer creditServer;HttpCreditGateway gateway;BureauEngine bureau;ResponseRepository repository;ResponseEngine responses;
    /** Creates isolated test resources and wires the workflow under test. */
    @BeforeEach void setup()throws Exception {
        store=new SqlStore("jdbc:h2:mem:"+UUID.randomUUID());customers=new CustomerEngine(store,new CobolUnderwriter(Path.of("../build/liuwbatch"),temp),clock);
        marketing=new MarketingEngine(store,new CobolMarketingQualifier(Path.of("../build/limkbatch"),temp),clock);
        db=new BureauDatabase(System.getenv("BUREAU_TEST_DATABASE_URL"),schema);queue=new BureauQueue(db,"test-"+schema+".");source=new MarketingSource(store,clock);
        policy=new CobolCreditPolicy(Path.of("../build/licbbatch"),2,clock);credit=new CreditEngine(db,policy,clock);creditServer=new CreditServer(credit,0,"a".repeat(40));creditServer.start();gateway=new HttpCreditGateway(creditServer.port(),"a".repeat(40));bureau=new BureauEngine(queue,source,gateway,clock,2);
        repository=new ResponseRepository(db,"test-"+schema+".");responses=new ResponseEngine(repository,source,clock);
    }
    /** Closes test resources and removes only the temporary artifacts owned by the test. */
    @AfterEach void cleanup(){if(responses!=null)responses.close();if(bureau!=null)bureau.close();if(creditServer!=null)creditServer.close();if(policy!=null)policy.close();if(customers!=null)customers.close();if(store!=null)store.close();if(db!=null){db.tx(c->{execute(c,"DROP SCHEMA "+schema+" CASCADE");return null;});db.close();}}
    /** Creates and finalizes the marketing qualification required by downstream tests. */
    Reference finalized(){customers.create(EngineTest.change(EngineTest.change(EngineTest.good(),"marketingOptIn",true),"externalReference",null));
        var population=customers.createPopulation(new PopulationRequest(List.of(Product.PERSONAL_LOAN),null));var run=marketing.preview(new RunRequest(UUID.randomUUID().toString(),population.id(),List.of("DEMO-PL")));marketing.finalizeRun(run.id());
        var q=marketing.results(run.id(),0,100,null).items().stream().filter(x->x.outcome().equals("QUALIFIED")).findFirst().orElseThrow();return new Reference(run.id(),q.riskId(),q.id());}
    /** Builds independent bureau facts for a controlled credit scenario. */
    void facts(Reference ref,Facts facts){String customer=source.current(ref).customer().customerId();var p=gateway.profile(customer);gateway.change(customer,new ProfileChange(p.version(),"LOCAL_IMPORT",facts));}
    /** Completes an approved bureau assessment for the test qualification. */
    Response approve(Reference ref,String request){facts(ref,CreditPolicyTest.facts());bureau.submit(new Submit(request,ref));bureau.processOne();responses.projectPending();return responses.get(ResponseRepository.id(ref));}
    /** Invokes the response HTTP API and returns its decoded result. */
    ApiServer api()throws Exception {var api=new ApiServer(customers,marketing,0,"t".repeat(40));api.bureau(new BureauApi(bureau,customers,null));api.responses(new ResponseApi(responses));api.start();return api;}
    /** Waits for the asynchronous test condition within its bounded timeout. */
    static void await(BooleanSupplier done)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);while(!done.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(100);assertTrue(done.getAsBoolean());}

    /** Verifies that publishes minimal versioned contract and only the qualified offer. */
    @Test void publishesMinimalVersionedContractAndOnlyTheQualifiedOffer()throws Exception {var ref=finalized();var response=approve(ref,"approve");assertTrue(response.marketingEligible());assertEquals("ELIGIBLE",response.status());assertEquals(1,response.version());assertEquals(ref.riskId(),response.source().riskId());assertEquals(response.qualification().offerId(),response.qualifiedOffer().id());assertEquals(response.qualification().offerVersion(),response.qualifiedOffer().version());
        assertEquals(source.current(ref).customer().data().personalLoanAmountUsd(),response.assessedAmountUsd());assertEquals("USD",response.currency());
        assertEquals(1,responses.list(response.customerId(),null,100).items().size());assertTrue(responses.list("different-customer",null,100).items().isEmpty());
        String payload=db.tx(c->scalar(c,"SELECT payload FROM bureau_outbox WHERE topic=?",repository.qualificationTopic));for(String privateField:List.of("creditScore","monthlyIncomeUsd","bureauProfile","displayName","subjectId"))assertFalse(payload.contains("\""+privateField+"\""));
        Files.writeString(Path.of("target/qualification-event-approved.json"),payload);
        responses.projectPending();bureau.submit(new Submit("approve",ref));assertEquals(1,responses.history(response.id(),0,100).size());assertEquals("3",db.tx(c->scalar(c,"SELECT count(*) FROM bureau_outbox")));
    }
    /** Verifies that declines and review are published but never visible as eligible. */
    @Test void declinesAndReviewArePublishedButNeverVisibleAsEligible(){var ref=finalized();facts(ref,CreditPolicyTest.change(CreditPolicyTest.facts(),"creditScore",600,Facts.class));bureau.submit(new Submit("decline",ref));bureau.processOne();responses.projectPending();var declined=responses.get(ResponseRepository.id(ref));assertEquals("DECLINED",declined.status());assertFalse(declined.marketingEligible());assertTrue(responses.list(declined.customerId(),null,100).items().isEmpty());
        facts(ref,CreditPolicyTest.change(CreditPolicyTest.facts(),"fileStatus",FileStatus.FROZEN,Facts.class));bureau.submit(new Submit("review",ref));bureau.processOne();responses.projectPending();assertEquals("REVIEW",responses.get(declined.id()).status());assertEquals(2,responses.history(declined.id(),0,100).size());
    }
    /** Verifies that background publishes consent withdrawal without an API read. */
    @Test void backgroundPublishesConsentWithdrawalWithoutAnApiRead()throws Exception {var ref=finalized();var r=approve(ref,"consent");responses.start();var customer=customers.get(r.customerId());customers.update(customer.id(),new Update(customer.current().version(),EngineTest.change(customer.current().data(),"marketingOptIn",false)));clock.now=clock.now.plusSeconds(6);
        await(()->!repository.get(r.id()).marketingEligible());assertEquals("REVOKED",repository.get(r.id()).status());assertEquals(2,responses.history(r.id(),0,100).size());assertTrue(responses.list(r.customerId(),null,100).items().isEmpty());
    }
    /** Verifies that suppression added then removed during outage still revokes on restart. */
    @Test void suppressionAddedThenRemovedDuringOutageStillRevokesOnRestart(){var ref=finalized();var r=approve(ref,"suppression");responses.close();
        marketing.saveSuppression(r.customerId(),new SuppressionUpdate(0,new SuppressionInput(true,"LOCAL_TEST",null)));marketing.saveSuppression(r.customerId(),new SuppressionUpdate(1,new SuppressionInput(false,"RELEASED",null)));
        responses=new ResponseEngine(repository,source,clock);clock.now=clock.now.plusSeconds(6);responses.reconcile();assertEquals("REVOKED",repository.get(r.id()).status());assertEquals(List.of("SUPPRESSION_ADDED_SINCE_QUALIFICATION"),repository.get(r.id()).reasons());assertEquals(2,responses.history(r.id(),0,100).size());responses.reconcile();assertEquals(2,responses.history(r.id(),0,100).size());
    }
    /** Verifies that cancelled campaign and catalog revision invalidate without changing historical terms. */
    @Test void cancelledCampaignAndCatalogRevisionInvalidateWithoutChangingHistoricalTerms(){var ref=finalized();var r=approve(ref,"catalog");var campaign=marketing.campaign(r.qualification().campaignId());marketing.saveCampaign(campaign.id(),new CampaignUpdate(campaign.version(),CreditPolicyTest.change(campaign.data(),"active",false,CampaignInput.class)));
        assertEquals("REVOKED",responses.get(r.id()).status());assertEquals(r.qualifiedOffer(),responses.get(r.id()).qualifiedOffer());assertFalse(responses.get(r.id()).marketingEligible());
    }
    /** Verifies that expiry is exclusive and replay never resurrects an approval. */
    @Test void expiryIsExclusiveAndReplayNeverResurrectsAnApproval()throws Exception {var ref=finalized();var r=approve(ref,"expiry");clock.now=Instant.parse(r.validUntil()).minusNanos(1);assertTrue(responses.get(r.id()).marketingEligible());clock.now=Instant.parse(r.validUntil());responses.reconcile();assertEquals("EXPIRED",repository.get(r.id()).status());var replay=responses.replay(r.id());assertEquals(2L,replay.get("version"));
        String payload=db.tx(c->scalar(c,"SELECT payload FROM bureau_outbox WHERE id=?",replay.get("eventId")));assertFalse(Json.MAPPER.readTree(payload).path("response").path("marketingEligible").asBoolean());
    }
    /** Verifies that newer completed decline hides prior approval before background projection. */
    @Test void newerCompletedDeclineHidesPriorApprovalBeforeBackgroundProjection(){var ref=finalized();var r=approve(ref,"older");facts(ref,CreditPolicyTest.change(CreditPolicyTest.facts(),"creditScore",600,Facts.class));bureau.submit(new Submit("newer",ref));bureau.processOne();assertTrue(repository.get(r.id()).marketingEligible());
        assertTrue(responses.list(r.customerId(),null,100).items().isEmpty());assertEquals("DECLINED",repository.get(r.id()).status());assertEquals(2,repository.get(r.id()).version());
    }
    /** Verifies that older request finishing late cannot overwrite newer result. */
    @Test void olderRequestFinishingLateCannotOverwriteNewerResult(){var ref=finalized();facts(ref,CreditPolicyTest.facts());bureau.submit(new Submit("old-slow",ref));var old=queue.claim();bureau.submit(new Submit("new-fast",ref));bureau.processOne();responses.projectPending();var before=responses.get(ResponseRepository.id(ref));assertTrue(before.marketingEligible());
        queue.finish(old,new Result(old.id(),ref.riskId(),ref,"FAILED",null,"OLD_TIMEOUT",clock.instant().toString()));responses.projectPending();var after=responses.get(before.id());assertEquals(before,after);assertEquals("2",db.tx(c->scalar(c,"SELECT count(*) FROM bureau_outbox WHERE topic=?",repository.decisionTopic)));assertEquals(1,responses.history(before.id(),0,100).size());
    }
    /**
     * Verifies that backlog recovery never publishes an approval superseded by an already completed
     * decline.
     */
    @Test void backlogRecoveryNeverPublishesAnApprovalSupersededByAnAlreadyCompletedDecline(){var ref=finalized();facts(ref,CreditPolicyTest.facts());bureau.submit(new Submit("old-approved",ref));bureau.processOne();facts(ref,CreditPolicyTest.change(CreditPolicyTest.facts(),"creditScore",600,Facts.class));bureau.submit(new Submit("new-declined",ref));bureau.processOne();responses.projectPending();var r=responses.get(ResponseRepository.id(ref));assertEquals("DECLINED",r.status());assertEquals(1,r.version());assertEquals(1,responses.history(r.id(),0,100).size());assertEquals("2",db.tx(c->scalar(c,"SELECT count(*) FROM bureau_outbox WHERE topic=?",repository.decisionTopic)));assertEquals("0",db.tx(c->scalar(c,"SELECT count(*) FROM bureau_outbox WHERE topic=? AND payload LIKE '%\"marketingEligible\":true%'",repository.qualificationTopic)));}
    /** Verifies that source cancellation before projection and technical failure remain distinct. */
    @Test void sourceCancellationBeforeProjectionAndTechnicalFailureRemainDistinct()throws Exception {var ref=finalized();facts(ref,CreditPolicyTest.facts());bureau.submit(new Submit("completed",ref));bureau.processOne();marketing.cancel(ref.runId());responses.projectPending();assertEquals("REVOKED",responses.get(ResponseRepository.id(ref)).status());
        var another=finalized();bureau.submit(new Submit("technical",another));var work=queue.claim();queue.finish(work,new Result(work.id(),another.riskId(),another,"FAILED",null,"RETRY_BUDGET_EXHAUSTED",clock.instant().toString()));responses.projectPending();var r=responses.get(ResponseRepository.id(another));assertEquals("FAILED",r.status());assertNull(r.bureauDecision());assertFalse(r.marketingEligible());
        Files.writeString(Path.of("target/qualification-event-failed.json"),db.tx(c->scalar(c,"SELECT payload FROM bureau_outbox WHERE topic=? AND payload LIKE '%technical%'",repository.qualificationTopic)));
    }
    /** Verifies that concurrent projection creates exactly one revision and atomic events. */
    @Test void concurrentProjectionCreatesExactlyOneRevisionAndAtomicEvents()throws Exception {var ref=finalized();facts(ref,CreditPolicyTest.facts());bureau.submit(new Submit("parallel",ref));bureau.processOne();var pending=repository.pending(100).get(0);var evidence=source.withEvidence(ref,e->e);var pool=Executors.newFixedThreadPool(6);
        try{var tasks=new ArrayList<Callable<Void>>();for(int i=0;i<12;i++)tasks.add(()->{repository.project(pending,evidence,clock.instant());return null;});for(var result:pool.invokeAll(tasks))result.get();}finally{pool.shutdownNow();}
        assertEquals(1,responses.history(ResponseRepository.id(ref),0,100).size());assertEquals("3",db.tx(c->scalar(c,"SELECT count(*) FROM bureau_outbox")));assertTrue(repository.pending(100).isEmpty());
    }
    /** Verifies that upgrades existing V1 data and backfills against current eligibility. */
    @Test void upgradesExistingV1DataAndBackfillsAgainstCurrentEligibility()throws Exception {var ref=finalized();facts(ref,CreditPolicyTest.facts());bureau.submit(new Submit("legacy",ref));bureau.processOne();var result=(Result)queue.get("legacy").get("result");String legacy=schema+"_v1";
        String v1;try(var in=getClass().getResourceAsStream("/db/bureau/V001__bureau.sql")){v1=new String(Objects.requireNonNull(in).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        db.tx(c->{execute(c,"CREATE SCHEMA "+legacy);execute(c,"SET LOCAL search_path TO "+legacy);execute(c,"CREATE TABLE bureau_schema(version int PRIMARY KEY,checksum varchar(64) NOT NULL)");for(String sql:v1.split(";"))if(!sql.isBlank())execute(c,sql);execute(c,"INSERT INTO bureau_schema VALUES(1,?)",hash(v1));execute(c,"INSERT INTO bureau_work(id,fingerprint,run_id,risk_id,qualification_id,status,result) VALUES(?,?,?,?,?,?,?)","legacy",hash(ref),ref.runId(),ref.riskId(),ref.qualificationId(),"COMPLETED",Json.write(result));BureauQueue.outbox(c,"legacy-original",queue.resultsTopic,ref.riskId(),result);return null;});
        marketing.cancel(ref.runId());
        try(var migrated=new BureauDatabase(System.getenv("BUREAU_TEST_DATABASE_URL"),legacy);var backfill=new ResponseEngine(new ResponseRepository(migrated,"migration-test."),source,clock)){
            assertEquals("3",migrated.tx(c->scalar(c,"SELECT count(*) FROM bureau_schema")));assertEquals(Json.write(result),migrated.tx(c->scalar(c,"SELECT result FROM bureau_work WHERE id='legacy'")));backfill.projectPending();assertEquals("REVOKED",backfill.get(ResponseRepository.id(ref)).status());assertEquals("3",migrated.tx(c->scalar(c,"SELECT count(*) FROM bureau_outbox")));
            try(var restarted=new BureauDatabase(System.getenv("BUREAU_TEST_DATABASE_URL"),legacy)){assertEquals("1",restarted.tx(c->scalar(c,"SELECT count(*) FROM decision_response_history")));}
        }finally{db.tx(c->{execute(c,"DROP SCHEMA "+legacy+" CASCADE");return null;});}
    }
    /** Verifies that projection failure rolls back checkpoint response and both events then recovers. */
    @Test void projectionFailureRollsBackCheckpointResponseAndBothEventsThenRecovers(){var ref=finalized();facts(ref,CreditPolicyTest.facts());bureau.submit(new Submit("atomic",ref));bureau.processOne();
        db.tx(c->{execute(c,"ALTER TABLE decision_response_history ADD CONSTRAINT reject_test CHECK(version<0)");return null;});responses.projectPending();assertEquals("0",db.tx(c->scalar(c,"SELECT count(*) FROM decision_responses")));assertEquals("1",db.tx(c->scalar(c,"SELECT count(*) FROM bureau_outbox")));assertEquals("false",db.tx(c->scalar(c,"SELECT response_projected::text FROM bureau_work WHERE id='atomic'")));
        db.tx(c->{execute(c,"ALTER TABLE decision_response_history DROP CONSTRAINT reject_test");execute(c,"UPDATE bureau_work SET response_retry_at=now()");return null;});responses.projectPending();assertTrue(responses.get(ResponseRepository.id(ref)).marketingEligible());assertEquals("3",db.tx(c->scalar(c,"SELECT count(*) FROM bureau_outbox")));assertEquals("0",db.tx(c->scalar(c,"SELECT count(*) FROM bureau_work WHERE response_error IS NOT NULL")));
    }
    /** Verifies that API and Real3270 show only eligible offers while audit retains revoked. */
    @Test void apiAndReal3270ShowOnlyEligibleOffersWhileAuditRetainsRevoked()throws Exception {var ref=finalized();var r=approve(ref,"terminal");try(var api=api();var terminal=new TerminalServer(0,new ApiClient(api.port(),"t".repeat(40)))){var client=new ApiClient(api.port(),"t".repeat(40));assertEquals(1,client.call("GET","customers/"+r.customerId()+"/eligible-offers",null).path("items").size());assertThrows(RuntimeException.class,()->client.call("GET","decision-responses?limit=101",null));
            terminal.start();Process process=new ProcessBuilder("s3270","-model","3279-2","127.0.0.1:"+terminal.port()).redirectError(ProcessBuilder.Redirect.DISCARD).start();try(var w=new PrintWriter(process.getOutputStream(),true);var in=new BufferedReader(new InputStreamReader(process.getInputStream()))){MarketingTest.cmd(w,in,"Wait(5,InputField)");MarketingTest.cmd(w,in,"String(\"2\")");MarketingTest.enter(w,in);MarketingTest.cmd(w,in,"String(\"7\")");MarketingTest.enter(w,in);MarketingTest.cmd(w,in,"String(\"9\")");MarketingTest.enter(w,in);MarketingTest.cmd(w,in,"String(\"6\")");MarketingTest.enter(w,in);assertTrue(MarketingTest.cmd(w,in,"Ascii()").contains("DECISION RESPONSE"));MarketingTest.cmd(w,in,"String(\"2\")");MarketingTest.enter(w,in);MarketingTest.cmd(w,in,"String(\""+r.customerId()+"\")");MarketingTest.enter(w,in);assertTrue(MarketingTest.cmd(w,in,"Ascii()").contains(r.qualifiedOffer().id()));marketing.cancel(ref.runId());MarketingTest.enter(w,in);String screen=MarketingTest.cmd(w,in,"Ascii()");assertTrue(screen.contains("No eligible offers"));assertFalse(screen.contains(r.qualifiedOffer().id()));MarketingTest.cmd(w,in,"Quit()");}finally{process.destroyForcibly();}
            assertTrue(client.call("GET","customers/"+r.customerId()+"/eligible-offers",null).path("items").isEmpty());assertEquals("REVOKED",client.call("GET","decision-responses/"+r.id(),null).path("status").asText());assertEquals(2,client.call("GET","decision-responses/"+r.id()+"/history",null).size());assertEquals(2,client.call("POST","decision-responses/"+r.id()+"/replay",Map.of()).path("version").asInt());
        }
    }
    /** Verifies that actual kafka consumer deduplicates replay and discards stale versions. */
    @Test void actualKafkaConsumerDeduplicatesReplayAndDiscardsStaleVersions()throws Exception {var ref=finalized();var r=approve(ref,"kafka");String bootstrap=System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS","127.0.0.1:9092");Properties p=new Properties();p.put("bootstrap.servers",bootstrap);
        var topics=List.of(queue.inputTopic,queue.resultsTopic,queue.dlqTopic,repository.qualificationTopic,repository.decisionTopic);
        try(var admin=org.apache.kafka.clients.admin.Admin.create(p);var transport=new KafkaTransport(bureau,bootstrap,List.of(repository.qualificationTopic,repository.decisionTopic))){
            transport.start();await(()->responses.history(r.id(),0,10).get(0).get("deliveryStatus").equals("PUBLISHED"));
            p.put("group.id",schema);p.put("auto.offset.reset","earliest");p.put("enable.auto.commit","false");p.put("key.deserializer","org.apache.kafka.common.serialization.StringDeserializer");p.put("value.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
            try(var consumer=new org.apache.kafka.clients.consumer.KafkaConsumer<String,String>(p)){consumer.subscribe(List.of(repository.qualificationTopic));var seen=new HashSet<String>();Response[] state={null};int[] count={0};
                Runnable poll=()->{for(var record:consumer.poll(Duration.ofMillis(250))){assertEquals(ref.riskId(),record.key());var event=Json.read(record.value(),com.lending.engine.response.domain.DecisionResponse.Event.class);count[0]++;if(seen.add(event.eventId())&&(state[0]==null||event.response().version()>state[0].version()))state[0]=event.response();}};
                await(()->{poll.run();return state[0]!=null;});assertTrue(state[0].marketingEligible());responses.replay(r.id());await(()->{poll.run();return count[0]>=2;});assertEquals(1,seen.size());
                marketing.cancel(ref.runId());responses.get(r.id());await(()->{poll.run();return state[0].version()==2;});assertFalse(state[0].marketingEligible());

                String oldId=(String)responses.history(r.id(),0,10).get(0).get("eventId");db.tx(c->{execute(c,"UPDATE bureau_outbox SET sent_at=NULL WHERE id=?",oldId);return null;});await(()->{poll.run();return count[0]>=4;});assertEquals(2,state[0].version());assertFalse(state[0].marketingEligible());assertEquals(2,seen.size());
            }
        }finally{try(var admin=org.apache.kafka.clients.admin.Admin.create(Map.of("bootstrap.servers",bootstrap))){admin.deleteTopics(topics).all().get(20,TimeUnit.SECONDS);}}
    }
}
