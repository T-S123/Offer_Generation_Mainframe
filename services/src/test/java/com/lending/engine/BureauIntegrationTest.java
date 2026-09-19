/**
 * Business-operations regression: Opt-in real PostgreSQL/HTTP/COBOL/3270 integration suite; each test owns
 * a fresh schema and preserves application data.
 */
package com.lending.engine;

import com.lending.engine.application.CustomerEngine;
import com.lending.engine.api.ApiServer;
import com.lending.engine.bureau.api.BureauApi;
import com.lending.engine.bureau.application.*;
import com.lending.engine.bureau.domain.Bureau.*;
import com.lending.engine.bureau.infrastructure.*;
import static com.lending.engine.bureau.infrastructure.BureauDatabase.*;
import com.lending.engine.credit.*;
import com.lending.engine.domain.Model.*;
import com.lending.engine.infrastructure.*;
import com.lending.engine.marketing.application.MarketingEngine;
import com.lending.engine.marketing.domain.Marketing.*;
import com.lending.engine.marketing.infrastructure.CobolMarketingQualifier;
import com.lending.engine.terminal.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;

/**
 * Business-operations regression: Opt-in real PostgreSQL/HTTP/COBOL/3270 integration suite; each test owns
 * a fresh schema and preserves application data.
 */
@EnabledIfEnvironmentVariable(named="BUREAU_TEST_DATABASE_URL",matches=".+")
class BureauIntegrationTest {
    @TempDir Path temp;String schema="bureau_test_"+UUID.randomUUID().toString().replace("-","");
    final Clock clock=Clock.fixed(CreditPolicyTest.NOW,ZoneOffset.UTC);
    SqlStore store;CustomerEngine customers;MarketingEngine marketing;BureauDatabase db;BureauQueue queue;MarketingSource source;CreditEngine credit;CreditServer creditServer;CobolCreditPolicy policy;HttpCreditGateway gateway;BureauEngine bureau;
    /** Creates isolated test resources and wires the workflow under test. */
    @BeforeEach void setup()throws Exception {
        store=new SqlStore("jdbc:h2:mem:"+UUID.randomUUID());customers=new CustomerEngine(store,new CobolUnderwriter(Path.of("../build/liuwbatch"),temp),clock);marketing=new MarketingEngine(store,new CobolMarketingQualifier(Path.of("../build/limkbatch"),temp),clock);
        db=new BureauDatabase(System.getenv("BUREAU_TEST_DATABASE_URL"),schema);queue=new BureauQueue(db,"test-"+schema+".");source=new MarketingSource(store,clock);policy=new CobolCreditPolicy(Path.of("../build/licbbatch"),4,clock);credit=new CreditEngine(db,policy,clock);creditServer=new CreditServer(credit,0,"a".repeat(40));creditServer.start();gateway=new HttpCreditGateway(creditServer.port(),"a".repeat(40));bureau=new BureauEngine(queue,source,gateway,clock,8);
    }
    /** Closes the isolated test services, stores and processes. */
    @AfterEach void close(){if(bureau!=null)bureau.close();if(creditServer!=null)creditServer.close();if(policy!=null)policy.close();if(customers!=null)customers.close();if(store!=null)store.close();if(db!=null){db.tx(c->{execute(c,"DROP SCHEMA "+schema+" CASCADE");return null;});db.close();}}
    /** Creates a customer in the isolated test store. */
    Customer customer(){return customers.create(EngineTest.change(EngineTest.change(EngineTest.good(),"marketingOptIn",true),"externalReference",null));}
    /** Creates a marketing qualification preview for the test customer population. */
    Run preview(){return marketing.preview(new RunRequest(UUID.randomUUID().toString(),customers.createPopulation(new PopulationRequest(List.of(Product.PERSONAL_LOAN),null)).id(),List.of("DEMO-PL")));}
    /** Returns the finalized qualification reference used by a bureau request. */
    Reference reference(Run run){var q=marketing.results(run.id(),0,100,null).items().stream().filter(v->v.outcome().equals("QUALIFIED")).findFirst().orElseThrow();return new Reference(run.id(),q.riskId(),q.id());}
    /** Creates and finalizes the marketing qualification required by downstream tests. */
    Reference finalized(){customer();var run=preview();marketing.finalizeRun(run.id());return reference(run);}
    /** Verifies that batch identifier conflict cannot link an unrelated request. */
    @Test void batchIdentifierConflictCannotLinkAnUnrelatedRequest(){var ref=finalized();var other=marketing.results(ref.runId(),0,100,null).items().stream().filter(q->q.outcome().equals("QUALIFIED")&&!q.id().equals(ref.qualificationId())).findFirst().orElseThrow();String id="B-"+hash(List.of("collision-batch",ref.runId(),ref.qualificationId())).substring(0,60);queue.submit(new Submit(id,new Reference(ref.runId(),other.riskId(),other.id())));bureau.batch(new BatchRequest("collision-batch",List.of(ref.runId())));queue.expand(source);assertEquals("COMPLETED_WITH_ERRORS",queue.batchStatus("collision-batch").get("status"));assertEquals("0",db.tx(c->scalar(c,"SELECT count(*) FROM bureau_batch_items")));}
    /** Verifies that streaming files use public API and replay batch without duplicate work. */
    @Test void streamingFilesUsePublicApiAndReplayBatchWithoutDuplicateWork()throws Exception {
        var ref=finalized();String id=source.current(ref).customer().customerId();
        try(var api=new ApiServer(customers,marketing,0,"f".repeat(40))){api.bureau(new BureauApi(bureau,customers,null));api.start();var client=new ApiClient(api.port(),"f".repeat(40));
            Path imports=temp.resolve("bureau-import.ndjson");Files.writeString(imports,Json.write(new com.lending.engine.bureau.api.BureauFiles.ProfileImport(id,0,CreditPolicyTest.facts()))+"\n");
            com.lending.engine.bureau.api.BureauFiles.run(client,"--import-bureau-profiles",imports);assertEquals("LOCAL_IMPORT",credit.profile(id).source());
            Path manifest=temp.resolve("batches.ndjson");Files.writeString(manifest,Json.write(new BatchRequest("file-batch",List.of(ref.runId())))+"\n");
            com.lending.engine.bureau.api.BureauFiles.run(client,"--bureau-batch",manifest);com.lending.engine.bureau.api.BureauFiles.run(client,"--bureau-batch",manifest);
            assertEquals("1",db.tx(c->scalar(c,"SELECT count(*) FROM bureau_batches")));assertThrows(RuntimeException.class,()->client.call("GET","bureau/sources/"+ref.runId()+"?after=invalid",null));
            assertThrows(IOException.class,()->com.lending.engine.bureau.api.BureauFiles.run(client,"--import-bureau-profiles",imports));assertEquals(1,credit.history(id,0,100).size());
        }
    }
    /** Verifies that credit service rejects wrong identity and conflicting replay. */
    @Test void creditServiceRejectsWrongIdentityAndConflictingReplay(){var ref=finalized();String id=source.current(ref).customer().customerId();var profile=gateway.profile(id);var input=CreditPolicyTest.input(Product.PERSONAL_LOAN);input=CreditPolicyTest.change(input,"customerId",id,CreditInput.class);input=CreditPolicyTest.change(input,"subjectId",profile.subjectId(),CreditInput.class);var accepted=gateway.assess(input);assertEquals(accepted,gateway.assess(input));
        var changed=CreditPolicyTest.change(input,"amountUsd",new java.math.BigDecimal("20000"),CreditInput.class);assertThrows(Problem.class,()->gateway.assess(changed));var wrong=CreditPolicyTest.change(CreditPolicyTest.change(input,"requestId","wrong-subject",CreditInput.class),"subjectId","unmapped",CreditInput.class);assertThrows(Problem.class,()->gateway.assess(wrong));assertEquals("1",db.tx(c->scalar(c,"SELECT count(*) FROM credit_assessments")));
    }
    /** Verifies that kafka consumes duplicates and poison records and publishes durable results. */
    @Test void kafkaConsumesDuplicatesAndPoisonRecordsAndPublishesDurableResults()throws Exception {
        String bootstrap=System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS","127.0.0.1:9092");var ref=finalized();importGood(ref);
        Properties adminProps=new Properties();adminProps.put("bootstrap.servers",bootstrap);
        try(var transport=new KafkaTransport(bureau,bootstrap);var admin=org.apache.kafka.clients.admin.Admin.create(adminProps)){
            transport.start();bureau.start(8);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
            while(!admin.listTopics().names().get().contains(queue.inputTopic)&&System.nanoTime()<deadline)Thread.sleep(100);
            Properties producerProps=new Properties();producerProps.putAll(adminProps);producerProps.put("key.serializer","org.apache.kafka.common.serialization.StringSerializer");producerProps.put("value.serializer","org.apache.kafka.common.serialization.StringSerializer");producerProps.put("acks","all");
            String event=Json.write(new KafkaTransport.Event("kafka-event",new Submit("kafka-request",ref)));
            try(var producer=new org.apache.kafka.clients.producer.KafkaProducer<String,String>(producerProps)){
                producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(queue.inputTopic,ref.riskId(),event)).get(10,TimeUnit.SECONDS);
                producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(queue.inputTopic,ref.riskId(),event)).get(10,TimeUnit.SECONDS);
                producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(queue.inputTopic,ref.riskId(),"{bad-json")).get(10,TimeUnit.SECONDS);
            }
            while(System.nanoTime()<deadline){if(db.tx(c->scalar(c,"SELECT count(*) FROM bureau_outbox WHERE sent_at IS NOT NULL")).equals("2"))break;Thread.sleep(100);}
            assertEquals("COMPLETED",queue.get("kafka-request").get("status"));assertEquals("1",db.tx(c->scalar(c,"SELECT count(*) FROM bureau_work")));assertEquals("2",db.tx(c->scalar(c,"SELECT count(*) FROM bureau_outbox WHERE sent_at IS NOT NULL")));
            Properties consumerProps=new Properties();consumerProps.putAll(adminProps);consumerProps.put("group.id",schema+"-audit");consumerProps.put("auto.offset.reset","earliest");consumerProps.put("enable.auto.commit","false");consumerProps.put("key.deserializer","org.apache.kafka.common.serialization.StringDeserializer");consumerProps.put("value.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
            boolean completed=false,rejected=false;try(var consumer=new org.apache.kafka.clients.consumer.KafkaConsumer<String,String>(consumerProps)){consumer.subscribe(List.of(queue.resultsTopic,queue.dlqTopic));deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!(completed&&rejected)&&System.nanoTime()<deadline){for(var record:consumer.poll(Duration.ofMillis(200))){var value=Json.MAPPER.readTree(record.value());if(record.topic().equals(queue.resultsTopic)){assertEquals(ref.riskId(),record.key());assertEquals("APPROVED",value.path("result").path("decision").path("outcome").asText());completed=true;}else{assertEquals("MALFORMED_EVENT",value.path("reason").asText());rejected=true;}}}}
            assertTrue(completed&&rejected);
        }finally{try(var admin=org.apache.kafka.clients.admin.Admin.create(adminProps)){admin.deleteTopics(List.of(queue.inputTopic,queue.resultsTopic,queue.dlqTopic)).all().get(15,TimeUnit.SECONDS);}}
    }
    /** Verifies that upstream profile suppression expiry and catalog changes are rechecked. */
    @Test void upstreamProfileSuppressionExpiryAndCatalogChangesAreRechecked(){
        var ref=finalized();var snapshot=source.current(ref);String id=snapshot.customer().customerId();
        marketing.saveSuppression(id,new SuppressionUpdate(0,new SuppressionInput(true,"TEST-HOLD",null)));assertThrows(Problem.class,()->source.current(ref));
        marketing.saveSuppression(id,new SuppressionUpdate(1,new SuppressionInput(false,"RELEASED",null)));assertNotNull(source.current(ref));
        assertThrows(Problem.class,()->new MarketingSource(store,Clock.fixed(clock.instant().plus(Duration.ofDays(8)),ZoneOffset.UTC)).current(ref));
        var offer=snapshot.offer();marketing.saveOffer(offer.id(),new OfferUpdate(offer.version(),CreditPolicyTest.change(offer.data(),"active",false,OfferInput.class)));assertThrows(Problem.class,()->source.current(ref));
        customers.update(id,new Update(snapshot.customer().version(),EngineTest.change(snapshot.customer().data(),"marketingOptIn",false)));assertThrows(Problem.class,()->source.current(ref));
    }
    /** Imports independent bureau facts that satisfy the tested policy. */
    void importGood(Reference ref){String customer=source.current(ref).customer().customerId();var p=gateway.profile(customer);gateway.change(customer,new ProfileChange(p.version(),"LOCAL_IMPORT",CreditPolicyTest.facts()));}
    /** Verifies that requires finalized Step2 and checks cancellation consent and offer changes. */
    @Test void requiresFinalizedStep2AndChecksCancellationConsentAndOfferChanges(){customer();var run=preview();var ref=reference(run);assertEquals(409,assertThrows(Problem.class,()->bureau.submit(new Submit("early",ref))).status);assertThrows(Problem.class,()->bureau.batch(new BatchRequest("early-batch",List.of(run.id()))));marketing.finalizeRun(run.id());bureau.submit(new Submit("before-cancel",ref));marketing.cancel(run.id());bureau.processOne();assertEquals("INVALIDATED",queue.get("before-cancel").get("status"));assertThrows(Problem.class,()->bureau.submit(new Submit("after-cancel",ref)));}
    /** Verifies that HTTP credit decision preserves risk id and replay is idempotent. */
    @Test void httpCreditDecisionPreservesRiskIdAndReplayIsIdempotent(){var ref=finalized();importGood(ref);bureau.submit(new Submit("manual",ref));bureau.processOne();var result=(Result)queue.get("manual").get("result");assertEquals("COMPLETED",result.status());assertEquals("APPROVED",result.decision().outcome());assertEquals(ref.riskId(),result.riskId());assertEquals(ref,result.source());assertEquals(2,result.decision().bureauProfileVersion());assertEquals("LOCAL_IMPORT",result.decision().bureauProfile().source());assertEquals("COMPLETED",bureau.submit(new Submit("manual",ref)).get("status"));assertThrows(Problem.class,()->bureau.submit(new Submit("manual",new Reference(ref.runId(),ref.riskId(),"different"))));assertEquals("1",db.tx(c->scalar(c,"SELECT count(*) FROM credit_assessments")));assertEquals("1",db.tx(c->scalar(c,"SELECT count(*) FROM bureau_outbox")));}
    /** Verifies that profile import and edits are versioned independent and optimistic. */
    @Test void profileImportAndEditsAreVersionedIndependentAndOptimistic(){var ref=finalized();String id=source.current(ref).customer().customerId();var p=gateway.profile(id);assertEquals(p,gateway.profile(id));assertEquals("SYNTHETIC-BUREAU-2026-001",p.source());var edited=gateway.change(id,new ProfileChange(1,"LOCAL_EDIT",CreditPolicyTest.change(CreditPolicyTest.facts(),"creditScore",610,Facts.class)));assertEquals(2,edited.version());assertThrows(Problem.class,()->gateway.change(id,new ProfileChange(1,"LOCAL_EDIT",CreditPolicyTest.facts())));assertEquals(2,credit.history(id,0,100).size());bureau.submit(new Submit("decline",ref));bureau.processOne();var result=(Result)queue.get("decline").get("result");assertEquals("DECLINED",result.decision().outcome());assertTrue(result.decision().reasons().contains("SCORE_BELOW_MINIMUM"));assertNotEquals(610,customers.get(id).current().data().creditScore());}
    /** Verifies that cancellation during remote decision prevents successful completion. */
    @Test void cancellationDuringRemoteDecisionPreventsSuccessfulCompletion(){var ref=finalized();importGood(ref);BureauPorts.CreditGateway delayed=new BureauPorts.CreditGateway(){
        /** Loads or constructs the independent bureau profile used by the test. */
        public BureauProfile profile(String id){return gateway.profile(id);}
        /** Builds a modified fixture for exercising version and eligibility changes. */
        public BureauProfile change(String id,ProfileChange c){return gateway.change(id,c);}
        /** Invokes the credit assessment boundary for the controlled test request. */
        public Assessment assess(CreditInput input){var answer=gateway.assess(input);marketing.cancel(ref.runId());return answer;}};
        try(var engine=new BureauEngine(queue,source,delayed,clock,1)){engine.submit(new Submit("race",ref));engine.processOne();assertEquals("INVALIDATED",queue.get("race").get("status"));}}
    /** Verifies that lease recovery fences old worker and avoids double publication. */
    @Test void leaseRecoveryFencesOldWorkerAndAvoidsDoublePublication(){var ref=finalized();bureau.submit(new Submit("lease",ref));Work old=queue.claim();assertNotNull(old);assertNull(queue.claim());db.tx(c->{execute(c,"UPDATE bureau_work SET lease_until=now()-interval '1 second' WHERE id='lease'");return null;});Work current=queue.claim();assertNotEquals(old.leaseToken(),current.leaseToken());var result=new Result("lease",ref.riskId(),ref,"FAILED",null,"TEST",clock.instant().toString());assertFalse(queue.finish(old,result));assertTrue(queue.finish(current,result));assertFalse(queue.finish(current,result));assertEquals("1",db.tx(c->scalar(c,"SELECT count(*) FROM bureau_outbox")));}
    /** Verifies that bureau outage retries without inventing declines. */
    @Test void bureauOutageRetriesWithoutInventingDeclines(){var ref=finalized();bureau.submit(new Submit("outage",ref));creditServer.close();creditServer=null;bureau.processOne();assertEquals("RETRY",queue.get("outage").get("status"));assertNull(queue.get("outage").get("result"));db.tx(c->{execute(c,"UPDATE bureau_work SET attempts=8,available_at=now() WHERE id='outage'");return null;});bureau.processOne();assertEquals("FAILED",queue.get("outage").get("status"));assertNull(((Result)queue.get("outage").get("result")).decision());}
    /** Verifies that batch expansion resumes with bounded pages and parallel workers. */
    @Test void batchExpansionResumesWithBoundedPagesAndParallelWorkers()throws Exception {
        int count=Integer.getInteger("bureau.benchmark.customers",120);for(int i=0;i<count;i++)customer();var run=preview();marketing.finalizeRun(run.id());bureau.batch(new BatchRequest("batch-test",List.of(run.id())));queue.expand(source);
        var resumed=new BureauQueue(db);while(!(Boolean)resumed.batchStatus("batch-test").get("expanded"))resumed.expand(source);
        long start=System.nanoTime();bureau.start(8);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(Math.max(60,count));Map<String,Object> status;
        do{Thread.sleep(100);status=queue.batchStatus("batch-test");}while(!status.get("status").toString().startsWith("COMPLETED")&&System.nanoTime()<deadline);
        assertEquals("COMPLETED",status.get("status"));@SuppressWarnings("unchecked")var counts=(Map<String,Long>)status.get("counts");assertEquals(run.qualifiedOffers(),counts.get("COMPLETED"));double seconds=(System.nanoTime()-start)/1e9;System.out.printf(Locale.ROOT,"Bureau measured %d real HTTP/COBOL/PostgreSQL qualifications in %.2f seconds (%.1f/sec)%n",run.qualifiedOffers(),seconds,run.qualifiedOffers()/seconds);
        assertEquals(run.qualifiedOffers(),Long.parseLong(db.tx(c->scalar(c,"SELECT count(*) FROM credit_assessments"))));assertEquals("COMPLETED",bureau.batch(new BatchRequest("batch-test",List.of(run.id()))).get("status"));}
    /** Verifies that real terminal navigates bureau menu and edits A profile through HTTP. */
    @Test void realTerminalNavigatesBureauMenuAndEditsAProfileThroughHttp()throws Exception {var ref=finalized();String customer=source.current(ref).customer().customerId();
        try(var api=new ApiServer(customers,marketing,0,"b".repeat(40))){api.bureau(new BureauApi(bureau,customers,null));api.start();try(var terminal=new TerminalServer(0,new ApiClient(api.port(),"b".repeat(40)))){terminal.start();Process process=new ProcessBuilder("s3270","-model","3279-2","127.0.0.1:"+terminal.port()).redirectError(ProcessBuilder.Redirect.DISCARD).start();try(var w=new PrintWriter(process.getOutputStream(),true);var r=new BufferedReader(new InputStreamReader(process.getInputStream()))){MarketingTest.cmd(w,r,"Wait(5,InputField)");MarketingTest.cmd(w,r,"String(\"2\")");MarketingTest.enter(w,r);MarketingTest.cmd(w,r,"String(\"7\")");MarketingTest.enter(w,r);MarketingTest.cmd(w,r,"String(\"9\")");MarketingTest.enter(w,r);assertTrue(MarketingTest.cmd(w,r,"Ascii()").contains("BUREAU QUALIFICATION"));MarketingTest.cmd(w,r,"String(\"4\")");MarketingTest.enter(w,r);MarketingTest.cmd(w,r,"String(\""+customer+"\")");MarketingTest.enter(w,r);assertTrue(MarketingTest.cmd(w,r,"Ascii()").contains("Bureau v1"));MarketingTest.cmd(w,r,"MoveCursor(8,25)");MarketingTest.cmd(w,r,"EraseEOF()");MarketingTest.cmd(w,r,"String(\"720\")");MarketingTest.cmd(w,r,"Enter()");MarketingTest.cmd(w,r,"Wait(5,Unlock)");assertTrue(MarketingTest.cmd(w,r,"Ascii()").contains("profile v2 saved"));assertEquals(720,credit.profile(customer).facts().creditScore());MarketingTest.cmd(w,r,"Quit()");}finally{process.destroyForcibly();}}}}
}
