package com.lending.engine.web;

import com.lending.engine.api.ApiServer;
import com.lending.engine.application.CustomerEngine;
import com.lending.engine.infrastructure.*;
import com.lending.engine.marketing.application.MarketingEngine;
import com.lending.engine.marketing.infrastructure.CobolMarketingQualifier;
import com.lending.engine.simulation.api.SimulationApi;
import com.lending.engine.simulation.application.*;
import com.lending.engine.simulation.infrastructure.*;
import java.net.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.UUID;

/** Disposable browser and cross-module QA fixture: real owner APIs and COBOL, with isolated stores and explicit cleanup. */
public final class GuiPreviewHarness {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args.length>0?args[0]:".").toAbsolutePath().normalize();
        var preview=start(root,8088);
        Runtime.getRuntime().addShutdownHook(new Thread(()->{try{preview.close();}catch(Exception ignored){}}));
        System.out.println("Disposable GUI QA at http://127.0.0.1:"+preview.port()+" — isolated databases; AI disabled.");
    }
    /** Starts the domain owners on ephemeral ports; callers own the returned fixture and must close it. */
    public static Preview start(Path root,int guiPort)throws Exception {
        String previousHome=System.getProperty("engine.home");
        Path scratch=Files.createTempDirectory("lending-gui-qa-");
        System.setProperty("engine.home",scratch.toString());
        var loader=new URLClassLoader(new URL[]{root.resolve("marketing-service/target/marketing-offers-1.0.0.jar").toUri().toURL()},ClassLoader.getPlatformClassLoader());
        var type=loader.loadClass("com.lending.offers.api.OfferServer");
        var analysis=type.getConstructors()[0].newInstance(null,null,0,"gui-test-analysis");
        type.getMethod("start").invoke(analysis);int port=(int)type.getMethod("port").invoke(analysis);
        var store=new SqlStore("jdbc:h2:mem:gui-"+UUID.randomUUID());
        var clock=Clock.systemUTC();
        var customers=new CustomerEngine(store,new CobolUnderwriter(root.resolve("build/liuwbatch"),scratch),clock);
        var marketing=new MarketingEngine(store,new CobolMarketingQualifier(root.resolve("build/limkbatch"),scratch),clock);
        marketing.published(new PublishedPolicies(new CobolBusinessPolicy(root)));
        var simulations=new SimulationEngine(new SimulationStore("jdbc:h2:mem:gui-business-"+UUID.randomUUID()),marketing,root,clock,SimulationEngine.httpAnalysis(port,"gui-test-analysis"));
        var owner=new ApiServer(customers,marketing,0,"gui-test-owner");
        owner.business(new SimulationApi(simulations));owner.start();
        var web=new WebServer(URI.create("http://127.0.0.1:"+owner.port()+"/api/v1/"),"gui-test-owner",root.resolve("frontend/dist"),guiPort);web.start();
        return new Preview(web.port(),owner.port(),()->{
            web.close();owner.close();simulations.close();customers.close();store.close();
            try{((AutoCloseable)analysis).close();loader.close();try(var paths=Files.walk(scratch)){for(Path p:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}catch(Exception ignored){}
            if(previousHome==null)System.clearProperty("engine.home");else System.setProperty("engine.home",previousHome);
        });

    }
    public record Preview(int port,int ownerPort,AutoCloseable resources) implements AutoCloseable {
        public void close()throws Exception{resources.close();}
    }
}

