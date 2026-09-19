/**
 * Customer Steps 1-8 host and isolated Business experiments share versioned COBOL rules through explicit
 * API boundaries.
 */
package com.lending.engine;

import com.lending.engine.api.ApiServer;
import com.lending.engine.application.CustomerEngine;
import com.lending.engine.infrastructure.*;
import com.lending.engine.terminal.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

/**
 * Customer Steps 1-8 host and isolated Business experiments share versioned COBOL rules through explicit
 * API boundaries.
 */
public final class Main {
    /**
     * Loads local configuration, wires service dependencies and starts the selected application entry
     * point.
     */
    public static void main(String[] args) throws Exception {
        Path root=Path.of(System.getProperty("engine.home",".")).toAbsolutePath().normalize();
        Path runtime=root.resolve("runtime");Files.createDirectories(runtime);
        try{Files.setPosixFilePermissions(runtime,PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){}
        Path key=runtime.resolve("api-token");
        if(!Files.exists(key)){
            byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
            Files.writeString(key,Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),StandardOpenOption.CREATE_NEW);
        }
        String token=Files.readString(key).trim();
        if(token.length()<32)throw new IllegalStateException("Local API token is invalid");
        int apiPort=Integer.getInteger("engine.api.port",8090),terminalPort=Integer.getInteger("engine.terminal.port",2323);
        if(args.length==2&&java.util.Set.of("--bureau-batch","--import-bureau-profiles").contains(args[0])){com.lending.engine.bureau.api.BureauFiles.run(new ApiClient(apiPort,token),args[0],Path.of(args[1]));return;}
        if(args.length==2 && args[0].equals("--import-decisions")) {
            var request=Json.MAPPER.readTree(Files.readString(Path.of(args[1])));
            var response=new ApiClient(apiPort,token).call("POST","underwriting/import",request);
            System.out.println("Imported/replayed "+response.size()+" decisions");return;
        }
        String databaseUrl=System.getenv("DATABASE_URL");
        Path bureauKey=runtime.resolve("bureau-api-token");
        if(!Files.exists(bureauKey)){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);try{Files.writeString(bureauKey,Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException ignored){}}
        String bureauToken=Files.readString(bureauKey).trim();if(bureauToken.length()<32)throw new IllegalStateException("Invalid bureau credential");
        int creditPort=Integer.getInteger("bureau.credit.port",8091);
        if(args.length==1&&args[0].equals("--credit-service")){
            if(databaseUrl==null)throw new IllegalStateException("DATABASE_URL is required for the credit service");
            var db=new com.lending.engine.bureau.infrastructure.BureauDatabase(databaseUrl);
            var policy=new com.lending.engine.credit.CobolCreditPolicy(root.resolve("build/licbbatch"),Integer.getInteger("credit.workers",4),Clock.systemUTC());
            var service=new com.lending.engine.credit.CreditServer(new com.lending.engine.credit.CreditEngine(db,policy,Clock.systemUTC()),creditPort,bureauToken);
            Runtime.getRuntime().addShutdownHook(new Thread(()->{service.close();policy.close();db.close();}));service.start();System.out.println("Local credit bureau API: 127.0.0.1:"+creditPort);return;
        }
        SqlStore store=new SqlStore("jdbc:h2:file:"+runtime.resolve("customer-engine")+";DB_CLOSE_ON_EXIT=FALSE");
        CustomerEngine engine=new CustomerEngine(store,new CobolUnderwriter(root.resolve("build/liuwbatch"),runtime.resolve("scratch")),Clock.systemUTC());
        var marketing=new com.lending.engine.marketing.application.MarketingEngine(store,
            new com.lending.engine.marketing.infrastructure.CobolMarketingQualifier(root.resolve("build/limkbatch"),runtime.resolve("scratch")),Clock.systemUTC());
        ApiServer api=new ApiServer(engine,marketing,apiPort,token);
        var published=new com.lending.engine.simulation.application.PublishedPolicies(new com.lending.engine.simulation.infrastructure.CobolBusinessPolicy(root));marketing.published(published);
        var business=new com.lending.engine.simulation.application.SimulationEngine(new com.lending.engine.simulation.infrastructure.SimulationStore("jdbc:h2:file:"+runtime.resolve("business-simulations")+";DB_CLOSE_ON_EXIT=FALSE"),marketing,root,Clock.systemUTC(),com.lending.engine.simulation.application.SimulationEngine.httpAnalysis(Integer.getInteger("marketing.api.port",8092),serviceKey(runtime.resolve("marketing-api-token"))));api.business(new com.lending.engine.simulation.api.SimulationApi(business));
        com.lending.engine.bureau.infrastructure.BureauDatabase bureauDb=databaseUrl==null?null:new com.lending.engine.bureau.infrastructure.BureauDatabase(databaseUrl);
        var bureau=bureauDb==null?null:new com.lending.engine.bureau.application.BureauEngine(new com.lending.engine.bureau.infrastructure.BureauQueue(bureauDb),new com.lending.engine.bureau.application.MarketingSource(store,Clock.systemUTC()),new com.lending.engine.bureau.infrastructure.HttpCreditGateway(creditPort,bureauToken),Clock.systemUTC(),Integer.getInteger("bureau.workers",8));
        if(bureau!=null)((com.lending.engine.bureau.application.MarketingSource)bureau.source).published(published);
        var responses=bureau==null?null:new com.lending.engine.response.application.ResponseEngine(new com.lending.engine.response.infrastructure.ResponseRepository(bureauDb,""),(com.lending.engine.response.application.ResponsePorts.Source)bureau.source,Clock.systemUTC());
        var kafka=bureau==null?null:new com.lending.engine.bureau.infrastructure.KafkaTransport(bureau,System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS","127.0.0.1:9092"),java.util.List.of(responses.repository.qualificationTopic,responses.repository.decisionTopic));
        com.lending.engine.storage.StorageViews storage;
        com.lending.engine.execution.ExecutionEngine execution;
        com.lending.engine.automation.PipelineEngine pipeline;
        if(bureau!=null){
            var source=new com.lending.engine.offers.OfferSource(engine,bureau,responses,root,Clock.systemUTC());String marketingToken=serviceKey(runtime.resolve("marketing-api-token"));int marketingPort=Integer.getInteger("marketing.api.port",8092);
            api.offers(new com.lending.engine.offers.OfferApi(source,serviceKey(runtime.resolve("marketing-source-token")),marketingToken,marketingPort));
            var client=new com.lending.engine.storage.StorageClient(marketingPort,marketingToken);String bootstrap=System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS","127.0.0.1:9092"),topic=System.getProperty("storage.kafka.topic","offers.storage.updated.v1");
            var customerCopy=new com.lending.engine.storage.OfferCopyLoader(new com.lending.engine.storage.OfferCopyStore(databaseUrl,System.getProperty("storage.customer.schema","customer_offer_read")),client,bootstrap,topic);
            var warehouseCopy=new com.lending.engine.storage.OfferCopyLoader(new com.lending.engine.storage.OfferCopyStore(databaseUrl,System.getProperty("storage.warehouse.schema","enterprise_offer_warehouse")),client,bootstrap,topic);
            storage=new com.lending.engine.storage.StorageViews(customerCopy,warehouseCopy,source);api.storage(storage);storage.start();
            pipeline=new com.lending.engine.automation.PipelineEngine(store,marketing,bureau,Clock.systemUTC());
            execution=new com.lending.engine.execution.ExecutionEngine(new com.lending.engine.execution.ExecutionStore(databaseUrl,System.getProperty("execution.db.schema","campaign_execution")),client,engine,marketing,source,root,Clock.systemUTC());api.execution(execution,pipeline);pipeline.start();execution.start();
        }else {storage=null;execution=null;pipeline=null;}
        if(bureau!=null){api.bureau(new com.lending.engine.bureau.api.BureauApi(bureau,engine,kafka));api.responses(new com.lending.engine.response.api.ResponseApi(responses));bureau.start(Integer.getInteger("bureau.workers",8));responses.start();kafka.start();}
        TerminalServer terminal=new TerminalServer(terminalPort,new ApiClient(apiPort,token));
        Runtime.getRuntime().addShutdownHook(new Thread(()->{terminal.close();api.close();business.close();if(pipeline!=null)pipeline.close();if(execution!=null)execution.close();if(storage!=null)storage.close();if(responses!=null)responses.close();if(kafka!=null)kafka.close();if(bureau!=null)bureau.close();engine.close();store.close();if(bureauDb!=null)bureauDb.close();}));
        api.start();terminal.start();
        System.out.println("Lending Intelligence Engine - local simulation");
        System.out.println("API: http://127.0.0.1:"+apiPort+"/api/v1 | TN3270: 127.0.0.1:"+terminalPort);
        System.out.println("Run scripts/terminal.ps1 from PowerShell to open the real terminal.");
        if(bureau==null)System.out.println("Automatic Steps 2-7 disabled: configure DATABASE_URL in runtime/local.env (see docs/bureau-qualification.md).");
    }
    /** Loads or creates the local credential used by an internal service boundary. */
    private static String serviceKey(Path path)throws Exception {if(!Files.exists(path)){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);try{Files.writeString(path,Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException ignored){}}String value=Files.readString(path).trim();if(value.length()<32)throw new IllegalStateException("Invalid service credential");return value;}
}
