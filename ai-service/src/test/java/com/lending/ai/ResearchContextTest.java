package com.lending.ai;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.lending.ai.Contracts.*;
import static com.lending.ai.Json.*;

/** Exercises automatic retrieval through the production toolbox, including stale, unapproved and wrong-product evidence. */
class ResearchContextTest {
    @TempDir Path directory;
    private Request request(String id){return new Request("1.0.0",id,"workflow","research",1,Instant.now().plusSeconds(60).toString(),
        obj("intent","Explore bureau minimum score 680 to 740. Keep underwriting fixed.","product","PERSONAL_LOAN","allowedParameterIds",List.of("/rules/bureau/minimumScore")));}
    @Test void approvedEvidenceIsPresentOnFirstModelTurnAndRemainsAuditable()throws Exception{
        try(var store=new FoundationTest().store()){
            var file=directory.resolve("policy.md");
            Files.writeString(file,"Synthetic personal loan policy: bureau minimum score may range from 680 to 740 inclusive with integer step 1. The analyst must review and approve this document before use.");
            var index=new PolicyIndex(directory,store,false);index.scan();var doc=index.documents().get(0);
            index.approve(doc.path("id").asText(),doc.path("hash").asText(),"ALL","US",LocalDate.now().plusDays(30).toString(),"analyst");
            var calls=new AtomicInteger();var reservations=new AtomicInteger();
            var tools=new AgentToolbox(index,new McpTools(directory.resolve("missing.json")),(r,c,t)->reservations.incrementAndGet());
            ModelClient model=(instructions,input,schema)->{
                calls.incrementAndGet();
                var evidence=input.at("/observations/0/result/evidence");
                assertFalse(evidence.isEmpty(),"Research must see approved excerpts before it can ask the analyst for them");
                assertTrue(evidence.get(0).path("quote").asText().contains("680"));
                var range=new ParameterRegistry.Range("/rules/bureau/minimumScore",new BigDecimal("680"),new BigDecimal("740"),BigDecimal.ONE,null,List.of(evidence.get(0).path("evidenceId").asText()),false);
                return new ModelClient.Reply(tree(new Decision("READY","Approved evidence supports the requested range",null,List.of("/rules/bureau/minimumScore"),List.of("underwriting"),List.of(range),List.of(),null)),"test-double",10,10);
            };
            var result=new AgentRunner(Role.RESEARCH,model,tools).audit(store).run(request("approved"),()->false);
            assertEquals("READY",result.decision().status());assertEquals(1,calls.get());assertEquals(1,reservations.get());
            assertEquals(1,result.observations().size());
            assertTrue(store.find("TOOL_CALL","approved:prefetch:1").isPresent());
            assertTrue(index.documents().get(0).path("approved").asBoolean(),"Reading evidence must not request or revoke approval");
            assertTrue(tools.initialObservations(Role.ORCHESTRATOR,request("other-role")).isEmpty());

            // No periodic scan is needed: changing bytes must exclude stale approval immediately.
            Files.writeString(file,"Updated personal loan bureau minimum score policy now permits only 700 through 740.");
            assertTrue(tools.initialObservations(Role.RESEARCH,request("edited")).get(0).at("/result/evidence").isEmpty());
            var changed=index.documents().get(0);assertFalse(changed.path("approved").asBoolean());
            index.approve(changed.path("id").asText(),changed.path("hash").asText(),"AUTO_LOAN","US",LocalDate.now().plusDays(30).toString(),"analyst");
            assertTrue(tools.initialObservations(Role.RESEARCH,request("wrong-product")).get(0).at("/result/evidence").isEmpty());
            assertEquals(1,calls.get(),"Retrieval checks must not consume model calls");
        }
    }
}
