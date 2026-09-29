package com.lending.ai;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static com.lending.ai.Json.*;

/** Invariant tests use isolated stores and synthetic inputs; no provider, catalog or real customer is contacted. */
class FoundationTest {
    @TempDir Path dir;
    Store store(){return new Store("jdbc:h2:mem:"+UUID.randomUUID()+";DATABASE_TO_LOWER=TRUE","sa","","ai_test");}
    static ObjectNode baseline(){return obj("offer",obj("name","Example","product","PERSONAL_LOAN","active",true,"startsOn","2026-01-01","endsOn","2027-01-01","minimumAmountUsd",1000,"maximumAmountUsd",50000,"illustrativeAprPct",12,"annualFeeUsd",0,"termMonths",36),"rules",obj("bureau",obj("minimumScore",680),"underwriting",obj("minimumScore",660)));}
    static ParameterRegistry.Range scoreRange(){return new ParameterRegistry.Range("/rules/bureau/minimumScore",new BigDecimal("680"),new BigDecimal("740"),BigDecimal.ONE,null,List.of("evidence"),false);}
    @Test void registryAndScopeProtectUnmentionedAndHiddenFields(){
        var registry=new ParameterRegistry();assertEquals(42,registry.all().size());var b=baseline();var c=b.deepCopy();
        ParameterRegistry.set(c,"/rules/underwriting/minimumScore",tree(600));
        var e=assertThrows(Json.Fault.class,()->registry.validatePatch(b,c,Set.of("/rules/bureau/minimumScore"),Set.of("underwriting")));assertEquals("SCOPE_VIOLATION",e.code);
        assertThrows(Json.Fault.class,()->registry.validateValue("/rules/bureau/minimumScore",tree(0),"PERSONAL_LOAN",false));
        assertThrows(Json.Fault.class,()->registry.levels(new ParameterRegistry.Range("/rules/bureau/minimumScore",BigDecimal.ZERO,new BigDecimal("700"),BigDecimal.ONE,null,List.of(),true),"PERSONAL_LOAN",tree(680),10));
        assertThrows(Json.Fault.class,()->registry.validateValue("/rules/bureau/maximumLtvPct",tree(110),"PERSONAL_LOAN",false));
    }
    @Test void searchIsBoundedReproducibleAndKeepsAllOtherFields(){
        var o=new Optimizer(new ParameterRegistry());var b=baseline();var allowed=Set.of("/rules/bureau/minimumScore");
        var a=o.explore(b,List.of(scoreRange()),allowed,Set.of("underwriting"),5,123);
        assertEquals(a,o.explore(b,List.of(scoreRange()),allowed,Set.of("underwriting"),5,123));assertTrue(a.size()<=5);
        for(var c:a){assertTrue(allowed.containsAll(ParameterRegistry.differences(b,c.configuration())));assertEquals(660,c.configuration().at("/rules/underwriting/minimumScore").asInt());}
    }
    @Test void differentObjectivesGetDifferentWinnersAndZeroSupportIsExcluded(){
        var a=new Optimizer.Score("high-acceptance","r1",100,30,90d,30d,27,"VALIDATION");
        var e=new Optimizer.Score("high-eligibility","r2",100,90,50d,90d,45,"VALIDATION");
        var tiny=new Optimizer.Score("tiny","r3",100,1,100d,1d,1,"VALIDATION");
        var w=Optimizer.rank(List.of(a,e,tiny),30,0,0);assertEquals(a,w.acceptance());assertEquals(e,w.eligibility());assertEquals(2,w.frontier().size());
    }
    @Test void atomicBudgetSurvivesConcurrentRequestsAndRejectsKeyReuse()throws Exception {
        try(var s=store()){
            s.budget("w",new Store.Budget(4,6,1000,new BigDecimal("1"),Instant.now().plusSeconds(60).toString(),1,0,2,1,0,0));
            var pool=Executors.newFixedThreadPool(8);try{
                var tasks=new ArrayList<Callable<Boolean>>();for(int i=0;i<12;i++){String key="e"+i;tasks.add(()->{try{return s.reserve("w",key,1,0,0,BigDecimal.ZERO);}catch(Json.Fault e){assertEquals("BUDGET_EXHAUSTED",e.code);return false;}});}
                int accepted=0;for(var f:pool.invokeAll(tasks))if(f.get())accepted++;assertEquals(4,accepted);assertEquals(4,s.budget("w").path("evaluations").asInt());
            }finally{pool.shutdownNow();}
        }
    }
    @Test void artifactsAreImmutableAndOptimisticUpdatesRejectStaleWriters(){
        try(var s=store()){var saved=s.create("W","one",obj("state","NEW"));s.update("W","one",saved.version(),obj("state","DONE"));assertThrows(Json.Fault.class,()->s.update("W","one",saved.version(),obj("state","BROKEN")));
            String ref=s.artifact((Object)obj("foo",1));assertEquals(1,s.artifact(ref).path("foo").asInt());assertEquals(ref,s.artifact((Object)obj("foo",1.0)));}
    }
    @Test void structuredOutputsRejectUnknownFieldsAndUnauthorizedParameters(){
        var base=obj("status","READY","summary","ok","clarification",null,"allowedParameterIds",List.of("/rules/bureau/minimumScore"),"lockedStages",List.of("underwriting"),"ranges",List.of(),"claims",List.of(),"toolCall",null);
        assertEquals("READY",Contracts.decision(base).status());base.put("secretCommand","publish");assertThrows(Json.Fault.class,()->Contracts.decision(base));
    }
}
