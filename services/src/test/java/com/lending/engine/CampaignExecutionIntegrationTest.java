/**
 * Automatic Steps 1-8 integration verifies customer-correlated files, Business operations, qualified
 * choices despite blocked delivery and immediate withdrawal.
 */
package com.lending.engine;
import com.lending.engine.automation.PipelineEngine;
import com.lending.engine.execution.*;
import com.lending.engine.domain.Model.*;
import com.lending.engine.bureau.domain.Bureau.*;
import com.lending.engine.bureau.infrastructure.KafkaTransport;
import com.lending.engine.storage.StorageClient;
import com.lending.engine.storage.*;
import com.lending.engine.marketing.domain.Marketing.*;
import com.lending.engine.terminal.*;
import com.lending.engine.infrastructure.Json;
import static com.lending.engine.bureau.infrastructure.BureauDatabase.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Automatic Steps 1-8 integration verifies customer-correlated files, Business operations, qualified
 * choices despite blocked delivery and immediate withdrawal.
 */
@EnabledIfEnvironmentVariable(named="BUREAU_TEST_DATABASE_URL",matches=".+")
class CampaignExecutionIntegrationTest {
    @TempDir Path temp;OfferCreationIntegrationTest host;ResponseIntegrationTest f;PipelineEngine pipeline;ExecutionEngine execution;
    /** Creates isolated test resources and wires the workflow under test. */
    @BeforeEach void setup()throws Exception{host=new OfferCreationIntegrationTest();host.temp=temp;host.setup();f=host.f;pipeline=new PipelineEngine(f.store,f.marketing,f.bureau,f.clock);execution=createExecution();host.api.execution(execution,pipeline);}
    /** Starts campaign execution against the isolated test stores. */
    private ExecutionEngine createExecution(){return new ExecutionEngine(new ExecutionStore(System.getenv("BUREAU_TEST_DATABASE_URL"),host.serviceSchema+"_x"),new StorageClient(host.servicePort,host.serviceToken),f.customers,f.marketing,host.source,Path.of("..").toAbsolutePath(),temp.resolve("outbound"),f.clock);}
    /** Closes test resources and removes only the temporary artifacts owned by the test. */
    @AfterEach void cleanup()throws Exception{if(pipeline!=null)pipeline.close();if(execution!=null)execution.close();if(f!=null)f.db.tx(c->{execute(c,"DROP SCHEMA IF EXISTS "+host.serviceSchema+"_x CASCADE");return null;});if(host!=null)host.cleanup();}
    /**
     * Verifies that no outbound channel still allows qualified customer choices but suppression blocks
     * immediately.
     */
    @Test @Timeout(180) void noOutboundChannelStillAllowsQualifiedCustomerChoicesButSuppressionBlocksImmediately()throws Exception{
        var client=new ApiClient(host.api.port(),host.operatorToken);var c=f.customers.create(EngineTest.change(ExecutionTest.opted(),"preferredChannels",List.of()));String customer=c.id();
        f.gateway.change(customer,new ProfileChange(0,"LOCAL_IMPORT",CreditPolicyTest.change(CreditPolicyTest.facts(),"reportedAt",f.clock.instant().toString(),Facts.class)));
        Files.createDirectories(temp.resolve("runtime"));Files.writeString(temp.resolve("runtime/marketing-source-token"),host.sourceToken);Files.writeString(temp.resolve("runtime/marketing-api-token"),host.serviceToken);
        host.kafka=new KafkaTransport(f.bureau,"127.0.0.1:9092",List.of(f.repository.qualificationTopic,f.repository.decisionTopic));host.kafka.start();
        var builder=new ProcessBuilder("java","-Dengine.home="+temp,"-Dengine.api.port="+host.api.port(),"-Dmarketing.api.port="+host.servicePort,"-Dmarketing.db.schema="+host.serviceSchema,"-Dmarketing.kafka.group="+host.serviceSchema,"-Dstorage.kafka.topic="+host.serviceSchema+".storage","-Dmarketing.kafka.topic="+f.repository.qualificationTopic,"-Dorg.slf4j.simpleLogger.defaultLogLevel=warn","-jar",Path.of("../marketing-service/target/marketing-offers-1.0.0.jar").toAbsolutePath().toString());builder.environment().put("DATABASE_URL",System.getenv("BUREAU_TEST_DATABASE_URL"));builder.redirectErrorStream(true).redirectOutput(temp.resolve("presentation-test.log").toFile());host.service=builder.start();
        var storageClient=new StorageClient(host.servicePort,host.serviceToken);host.copies=new StorageViews(new OfferCopyLoader(new OfferCopyStore(System.getenv("BUREAU_TEST_DATABASE_URL"),host.serviceSchema+"_c"),storageClient,"127.0.0.1:9092",host.serviceSchema+".storage"),new OfferCopyLoader(new OfferCopyStore(System.getenv("BUREAU_TEST_DATABASE_URL"),host.serviceSchema+"_w"),storageClient,"127.0.0.1:9092",host.serviceSchema+".storage"),host.source);host.api.storage(host.copies);host.copies.start();
        f.bureau.start(2);f.responses.start();pipeline.start();execution.start();
        ResponseIntegrationTest.await(()->{f.clock.now=Instant.now();try{return !client.call("GET","customers/"+customer+"/offers",null).path("items").isEmpty()&&Json.write(execution.store.tasks(customer)).contains("NO_ENABLED_CHANNEL");}catch(Exception e){return false;}});
        assertTrue(client.call("GET","customers/"+customer+"/campaign-packages",null).path("items").isEmpty());var offer=client.call("GET","customers/"+customer+"/offers",null).path("items").get(0);String id=offer.path("id").asText();var request=Map.of("requestId","onscreen-original","sourceVersion",offer.path("qualification").path("responseVersion").asLong(),"kind","ORIGINAL","terms",offer.path("preScreen").path("terms"));
        assertEquals("ORIGINAL",client.call("POST","offer-creation/customers/"+customer+"/offers/"+id+"/selections",request).path("kind").asText());assertTrue(client.call("GET","customers/"+customer+"/campaign-packages",null).path("items").isEmpty());
        try(var terminal=new TerminalServer(0,client)){terminal.start();var process=new ProcessBuilder("s3270","-model","3279-2","127.0.0.1:"+terminal.port()).redirectError(ProcessBuilder.Redirect.DISCARD).start();try(var w=new PrintWriter(process.getOutputStream(),true);var r=new BufferedReader(new InputStreamReader(process.getInputStream()))){MarketingTest.cmd(w,r,"Wait(5,InputField)");for(String input:List.of("1","2")){MarketingTest.cmd(w,r,"String(\""+input+"\")");MarketingTest.enter(w,r);}MarketingTest.cmd(w,r,"MoveCursor(18,18)");MarketingTest.cmd(w,r,"String(\""+customer+"\")");MarketingTest.enter(w,r);MarketingTest.cmd(w,r,"PF(6)");MarketingTest.cmd(w,r,"Wait(5,InputField)");MarketingTest.cmd(w,r,"String(\"1\")");MarketingTest.enter(w,r);assertTrue(MarketingTest.cmd(w,r,"Ascii()").contains("Compare Your Offers"));
            f.marketing.saveSuppression(customer,new SuppressionUpdate(0,new SuppressionInput(true,"PRESENTATION_TEST",null)));MarketingTest.enter(w,r);String screen=MarketingTest.cmd(w,r,"Ascii()");assertTrue(screen.contains("Offer Information Unavailable"),screen);assertFalse(screen.contains("Est. monthly payment"));MarketingTest.cmd(w,r,"Quit()");}finally{process.destroyForcibly();}}
        assertTrue(client.call("GET","customers/"+customer+"/offers",null).path("items").isEmpty());assertThrows(IllegalArgumentException.class,()->client.call("POST","offer-creation/customers/"+customer+"/offers/"+id+"/selections",request));
    }
    /** Verifies that customer automatically gets personalized package selection revision and withdrawal. */
    @Test @Timeout(180) void customerAutomaticallyGetsPersonalizedPackageSelectionRevisionAndWithdrawal()throws Exception{
        var client=new ApiClient(host.api.port(),host.operatorToken);var c=client.call("POST","customers",ExecutionTest.opted());String customer=c.path("id").asText();assertEquals("QUEUED",c.path("pipeline").path("state").asText());assertEquals("SYNTHETIC",c.path("contact").path("source").asText());

        f.gateway.change(customer,new ProfileChange(0,"LOCAL_IMPORT",CreditPolicyTest.change(CreditPolicyTest.facts(),"reportedAt",f.clock.instant().toString(),Facts.class)));
        var denied=f.customers.create(EngineTest.change(EngineTest.good(),"externalReference","NO-CONSENT"));
        Files.createDirectories(temp.resolve("runtime"));Files.writeString(temp.resolve("runtime/marketing-source-token"),host.sourceToken);Files.writeString(temp.resolve("runtime/marketing-api-token"),host.serviceToken);
        host.kafka=new KafkaTransport(f.bureau,"127.0.0.1:9092",List.of(f.repository.qualificationTopic,f.repository.decisionTopic));host.kafka.start();
        var builder=new ProcessBuilder("java","-Dengine.home="+temp,"-Dengine.api.port="+host.api.port(),"-Dmarketing.api.port="+host.servicePort,"-Dmarketing.db.schema="+host.serviceSchema,"-Dmarketing.kafka.group="+host.serviceSchema,"-Dstorage.kafka.topic="+host.serviceSchema+".storage","-Dmarketing.kafka.topic="+f.repository.qualificationTopic,"-Dorg.slf4j.simpleLogger.defaultLogLevel=warn","-jar",Path.of("../marketing-service/target/marketing-offers-1.0.0.jar").toAbsolutePath().toString());builder.environment().put("DATABASE_URL",System.getenv("BUREAU_TEST_DATABASE_URL"));builder.redirectErrorStream(true).redirectOutput(temp.resolve("marketing-auto.log").toFile());host.service=builder.start();
        f.bureau.start(2);f.responses.start();pipeline.start();execution.start();
        try{ResponseIntegrationTest.await(()->{f.clock.now=Instant.now();try{return !client.call("GET","customers/"+customer+"/campaign-packages",null).path("items").isEmpty();}catch(Exception e){return false;}});}catch(AssertionError error){System.err.println("PIPELINE "+Json.write(f.customers.pipeline(customer)));System.err.println("EXECUTION "+Json.write(execution.health())+" "+Json.write(execution.store.tasks(customer)));System.err.println("MARKETING LOG "+Files.readString(temp.resolve("marketing-auto.log")));System.err.println("MODELS "+client.call("GET","offer-creation/models",null));System.err.println("OFFERS "+client.call("GET","offer-creation/customers/"+customer+"/offers",null));throw error;}
        var all=client.call("GET","customers/"+customer+"/campaign-packages",null);var p=all.path("items").get(0);String id=p.path("id").asText(),response=p.path("leadResponseId").asText();assertEquals("PERSONALIZED",p.path("leadKind").asText());assertTrue(p.path("alternatives").size()>=2);assertTrue(p.path("contact").path("email").asText().endsWith(".invalid"));assertEquals("EMAIL",p.path("channel").asText());assertEquals("COMPLETED",client.call("GET","customers/"+customer,null).path("pipeline").path("state").asText());assertEquals("NO_ELIGIBLE_OFFERS",f.customers.pipeline(denied.id()).state());
        var models=client.call("GET","offer-creation/models",null);assertFalse(models.isEmpty());assertTrue(models.get(0).path("metrics").path("testCount").asInt()>0);var file=client.call("GET","campaign-execution/packages/"+id+"/files?format=dat",null);assertEquals(OutboundFiles.RECORD_LENGTH*p.path("alternatives").size(),Base64.getDecoder().decode(file.path("contentBase64").asText()).length);Files.writeString(Path.of("target/campaign-package-example.json"),p.toPrettyString());
        var scopedFile=client.call("GET","customers/"+customer+"/campaign-packages/"+id+"/files?format=dat",null);assertEquals(file.path("sha256"),scopedFile.path("sha256"));assertThrows(IllegalArgumentException.class,()->client.call("GET","customers/"+denied.id()+"/campaign-packages/"+id+"/files",null));
        long sourceVersion=p.path("alternatives").get(0).path("responseVersion").asLong();client.call("POST","offer-creation/offers/"+response+"/selections",Map.of("requestId","prefer-original","sourceVersion",sourceVersion,"kind","ORIGINAL"));ResponseIntegrationTest.await(()->{try{return execution.current(id).leadKind().equals("ORIGINAL");}catch(Exception e){return false;}});var selected=execution.current(id);assertEquals("prefer-original",selected.selectionId());assertEquals(p.path("alternatives").size(),selected.alternatives().size());assertEquals("1",execution.store.tx(db->scalar(db,"SELECT count(*) FROM execution_dispatches WHERE package_id=?",id)));
        try(var terminal=new TerminalServer(0,client)){terminal.start();var process=new ProcessBuilder("s3270","-model","3279-2","127.0.0.1:"+terminal.port()).redirectError(ProcessBuilder.Redirect.DISCARD).start();try(var w=new PrintWriter(process.getOutputStream(),true);var r=new BufferedReader(new InputStreamReader(process.getInputStream()))){MarketingTest.cmd(w,r,"Wait(5,InputField)");for(String input:List.of("2","7","11","1","1")){MarketingTest.cmd(w,r,"String(\""+input+"\")");MarketingTest.enter(w,r);}String screen=MarketingTest.cmd(w,r,"Ascii()");assertTrue(screen.contains("CAMPAIGN EXECUTION"),screen);assertTrue(screen.contains("offers.dat"),screen);assertTrue(screen.contains("ORIGINAL"),screen);MarketingTest.cmd(w,r,"String(\"D\")");MarketingTest.enter(w,r);assertTrue(MarketingTest.cmd(w,r,"Ascii()").contains("verified"));MarketingTest.cmd(w,r,"Quit()");}finally{process.destroyForcibly();}}
        long after=execution.store.cursor(),version=selected.version();execution.close();Files.delete(temp.resolve("outbound").resolve(id).resolve("offers.csv"));execution=createExecution();host.api.execution(execution,pipeline);execution.start();assertTrue(execution.store.cursor()>=after);ResponseIntegrationTest.await(()->{try{var resumed=execution.current(id);return resumed.version()>=version&&resumed.selectionId().equals("prefer-original")&&execution.files.ready(resumed);}catch(Exception e){return false;}});assertEquals("1",execution.store.tx(db->scalar(db,"SELECT count(*) FROM execution_dispatches WHERE package_id=?",id)));

        var current=f.customers.get(customer);f.clock.now=f.clock.now.plusSeconds(1);client.call("PUT","customers/"+customer,new Update(current.current().version(),EngineTest.change(current.current().data(),"marketingOptIn",false)));assertThrows(IllegalArgumentException.class,()->client.call("GET","campaign-execution/packages/"+id+"/files",null));ResponseIntegrationTest.await(()->{try{return Json.MAPPER.readTree(Files.readString(temp.resolve("outbound").resolve(id).resolve("manifest.json"))).path("status").asText().equals("WITHDRAWN");}catch(Exception e){return false;}});assertFalse(Files.exists(temp.resolve("outbound").resolve(id).resolve("offers.csv")));assertTrue(client.call("GET","customers/"+customer+"/campaign-packages",null).path("items").isEmpty());assertTrue(Json.MAPPER.valueToTree(execution.store.history(id,0)).size()>=3);
    }
}
