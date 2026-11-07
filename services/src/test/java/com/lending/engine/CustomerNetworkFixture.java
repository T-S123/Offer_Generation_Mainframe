package com.lending.engine;

import com.lending.engine.offers.*;
import com.lending.engine.api.ApiServer;
import com.lending.engine.response.api.ResponseApi;
import com.lending.engine.domain.Model.*;
import com.lending.engine.bureau.domain.Bureau.*;
import com.lending.engine.infrastructure.Json;
import com.lending.engine.web.WebServer;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Disposable owner HTTP fixture: real COBOL and PostgreSQL, explicit qualification delivery, no Kafka or model calls. */
public final class CustomerNetworkFixture {
    public static void main(String[] args)throws Exception{
        Path output=Path.of(args[0]),root=Path.of("..").toAbsolutePath().normalize();var resources=new ArrayList<AutoCloseable>();
        var f=new ResponseIntegrationTest();f.temp=Files.createTempDirectory("customer-network-");f.clock.now=Instant.now();
        try{
            System.setProperty("engine.home",f.temp.toString());Files.createDirectories(f.temp.resolve("runtime/ai"));Files.writeString(f.temp.resolve("runtime/ai/credentials.json"),Json.write(Map.of("analyst","a".repeat(40))));
            f.setup();resources.add(f::cleanup);var first=f.finalized();
            f.facts(first,CreditPolicyTest.change(CreditPolicyTest.facts(),"reportedAt",f.clock.instant().toString(),Facts.class));
            f.bureau.submit(new Submit("prior-profile",first));f.bureau.processOne();f.responses.projectPending();
            String customer=f.source.current(first).customer().customerId();f.marketing.cancel(first.runId());var saved=f.customers.get(customer);
            f.customers.update(customer,new Update(saved.current().version(),EngineTest.change(saved.current().data(),"monthlyIncomeUsd",new java.math.BigDecimal("9500"))));
            var run=f.marketing.automatic(customer,f.customers.pipeline(customer).token());
            var qualified=f.marketing.results(run.id(),0,100,null).items().stream().filter(q->q.outcome().equals("QUALIFIED")&&q.offerId().equals("DEMO-PL-1")).findFirst().orElseThrow();
            var reference=new Reference(run.id(),qualified.riskId(),qualified.id());
            f.facts(reference,CreditPolicyTest.change(CreditPolicyTest.facts(),"reportedAt",f.clock.instant().toString(),Facts.class));
            f.bureau.submit(new Submit("current-profile",reference));f.bureau.processOne();f.responses.projectPending();
            var response=f.responses.get(com.lending.engine.response.infrastructure.ResponseRepository.id(reference));
            if(!response.marketingEligible())throw new IllegalStateException("Fixture customer must qualify");
            var source=new OfferSource(f.customers,f.bureau,f.responses,root,f.clock);
            var api=new ApiServer(f.customers,f.marketing,0,"fixture-owner");resources.add(api);api.responses(new ResponseApi(f.responses));api.start();
            var loader=new URLClassLoader(new URL[]{root.resolve("marketing-service/target/marketing-offers-1.0.0.jar").toUri().toURL()},ClassLoader.getPlatformClassLoader());resources.add(loader);Class.forName("org.postgresql.Driver",true,loader);
            var dbType=loader.loadClass("com.lending.offers.infrastructure.Database");var db=dbType.getConstructor(String.class,String.class).newInstance(System.getenv("BUREAU_TEST_DATABASE_URL"),"customer_smoke_"+UUID.randomUUID().toString().replace("-",""));resources.add((AutoCloseable)db);
            var storeType=loader.loadClass("com.lending.offers.infrastructure.OfferStore");var store=storeType.getConstructor(dbType).newInstance(db);
            var http=loader.loadClass("com.lending.offers.infrastructure.HttpSource").getConstructor(int.class,String.class).newInstance(api.port(),"fixture-source");
            var engineType=loader.loadClass("com.lending.offers.application.OfferEngine");var offers=engineType.getConstructors()[0].newInstance(store,http);resources.add((AutoCloseable)offers);
            var serverType=loader.loadClass("com.lending.offers.api.OfferServer");var server=serverType.getConstructors()[0].newInstance(offers,null,0,"fixture-offers");resources.add((AutoCloseable)server);serverType.getMethod("start").invoke(server);
            int port=(int)serverType.getMethod("port").invoke(server);api.offers(new OfferApi(source,"fixture-source","fixture-offers",port));
            engineType.getMethod("backfill").invoke(offers);for(int i=0;i<20;i++)engineType.getMethod("process").invoke(offers);
            var web=new WebServer(URI.create("http://127.0.0.1:"+api.port()+"/api/v1/"),"fixture-owner",root.resolve("frontend/dist"),0);web.start();resources.add(web);
            Files.writeString(output,Json.write(Map.of("ownerPort",api.port(),"webPort",web.port(),"customerId",customer,"profileVersion",2,"responseId",response.id())));
            System.in.read();
        }finally{Collections.reverse(resources);for(var resource:resources)try{resource.close();}catch(Exception ignored){}try(var files=Files.walk(f.temp)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
}
