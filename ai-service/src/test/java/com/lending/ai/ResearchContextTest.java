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

/** Exercises local and read-only cross-network policy retrieval, including stale, unapproved and wrong-product evidence. */
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
    @Test void feedbackRetrievesApprovedPolicyThroughReadOnlyOwnerApi()throws Exception {
        try(var store=new FoundationTest().store()){
            var file=directory.resolve("policy.md");Files.writeString(file,"Personal loan bureau minimum score policy permits scores from 680 through 740.");
            var index=new PolicyIndex(directory,store,false);index.scan();var doc=index.documents().get(0);
            index.approve(doc.path("id").asText(),doc.path("hash").asText(),"PERSONAL_LOAN","US",LocalDate.now().plusDays(30).toString(),"analyst");
            try(var a2a=new A2AService(Role.RESEARCH,store,new AgentRunner(Role.RESEARCH,(i,p,s)->{throw new AssertionError("No model needed for retrieval");},NetworkIsolationTest.unused()));
                var server=new Server(0,Role.RESEARCH,"analyst",Map.of("policyReader","read-only-token","analyst","owner-token"),a2a,store,null,null,null,index,null)){
                server.start();var reader=new Http("http://127.0.0.1:"+server.port(),"read-only-token");
                var tools=new AgentToolbox(null,new McpTools(directory.resolve("missing.json"),Network.FEEDBACK),(r,c,t)->{}).policyReader(reader);
                var base=request("feedback-policy");var payload=(com.fasterxml.jackson.databind.node.ObjectNode)base.payload().deepCopy();payload.put("network","FEEDBACK");
                var request=new Request(base.schemaVersion(),base.requestId(),base.workflowId(),base.stepId(),base.planRevision(),base.deadline(),payload);
                assertFalse(tools.initialObservations(Role.RESEARCH,request).get(0).at("/result/evidence").isEmpty());
                assertThrows(Fault.class,()->reader.call("POST","api/v1/ai/policies/"+doc.path("id").asText()+"/approve",obj("hash",doc.path("hash"),"product","ALL","geography","US","expiresOn",LocalDate.now().plusDays(60).toString())));
                Files.writeString(file,"Updated personal loan bureau minimum score requires 700.");
                assertTrue(tools.initialObservations(Role.RESEARCH,request).get(0).at("/result/evidence").isEmpty());
            }
        }
    }
}
