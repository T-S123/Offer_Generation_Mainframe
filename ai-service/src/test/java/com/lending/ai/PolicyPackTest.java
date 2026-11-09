package com.lending.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.lending.ai.Contracts.*;
import static com.lending.ai.Json.*;

/** Exercises the shipped policy pack through extraction, bounded retrieval and first-turn Research, using isolated stores and no provider. */
class PolicyPackTest {
    @TempDir Path folder;
    private void copyPack() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        if(!Files.isDirectory(root.resolve("policy-documents")))root=root.getParent();
        for(String name:List.of("a2a-personal-loan-policy.md","a2a-simulation-operating-policy.md")){
            byte[] live=Files.readAllBytes(root.resolve("policy-documents").resolve(name));
            assertArrayEquals(live,Files.readAllBytes(root.resolve("app/data/policies").resolve(name)),
                "Presenter preparation must install the same policy version Research reads");
            Files.write(folder.resolve(name),live);
        }
    }
    @Test void longerPackSuppliesApprovedRangeOnFirstModelTurnWithoutAnAnalystQuestion() throws Exception {
        copyPack();
        try(var store=new FoundationTest().store()){
            var index=new PolicyIndex(folder,store,false);index.scan();
            assertEquals(2,index.documents().size());
            assertTrue(index.search("rules bureau minimum Score","PERSONAL_LOAN",8).isEmpty());
            for(var document:index.documents()){
                assertEquals("READY",document.path("extractionStatus").asText());
                index.approve(document.path("id").asText(),document.path("hash").asText(),"PERSONAL_LOAN","US",
                    LocalDate.now().plusDays(30).toString(),"test-reviewer");
            }
            var calls=new AtomicInteger();
            var tools=new AgentToolbox(index,new McpTools(folder.resolve("no-mcp.json")),(r,c,t)->{});
            ModelClient model=(instructions,input,schema)->{
                calls.incrementAndGet();
                var evidence=input.at("/observations/0/result/evidence");
                assertFalse(evidence.isEmpty());
                var hit=java.util.stream.StreamSupport.stream(evidence.spliterator(),false)
                    .filter(e->e.path("source").asText().equals("a2a-personal-loan-policy.md")
                        && e.path("quote").asText().contains("minimum 680, maximum 740"))
                    .findFirst().orElseThrow(()->new AssertionError("Approved mandate was lost behind market/statistics excerpts"));
                var range=new ParameterRegistry.Range("/rules/bureau/minimumScore",new BigDecimal("680"),
                    new BigDecimal("740"),BigDecimal.ONE,null,List.of(hit.path("evidenceId").asText()),false);
                return new ModelClient.Reply(tree(new Decision("READY","Research has the applicable approved mandate",
                    null,List.of("/rules/bureau/minimumScore"),List.of("underwriting"),List.of(range),List.of(),null)),
                    "test-double",10,10);
            };
            var request=new Request("1.0.0","pack-research","pack-workflow","research",1,
                Instant.now().plusSeconds(60).toString(),obj("intent","Explore bureau minimum score",
                    "product","PERSONAL_LOAN","allowedParameterIds",List.of("/rules/bureau/minimumScore")));
            var result=new AgentRunner(Role.RESEARCH,model,tools).run(request,()->false);
            assertEquals("READY",result.decision().status());assertEquals(1,calls.get());
            assertNull(result.decision().question());
            var range=result.decision().ranges().get(0);
            index.validateEvidence("PERSONAL_LOAN",range.evidenceIds());
            assertTrue(index.search("bureau minimum score","AUTO_LOAN",8).isEmpty(),
                "Cross-product market context must not make this mandate applicable to auto loans");
        }
    }
    @Test void marketAndMeasurementContextIsRetrievableWithoutReplacingOwnerMeasurements() throws Exception {
        copyPack();
        try(var store=new FoundationTest().store()){
            var index=new PolicyIndex(folder,store,false);index.scan();
            for(var d:index.documents())index.approve(d.path("id").asText(),d.path("hash").asText(),
                "PERSONAL_LOAN","US",LocalDate.now().plusDays(30).toString(),"test-reviewer");
            var market=index.search("APR 11.90 Q3 2026","PERSONAL_LOAN",8);
            assertTrue(market.stream().anyMatch(h->h.path("quote").asText().contains("11.90%")
                && h.path("source").asText().equals("a2a-personal-loan-policy.md")));
            var measurement=index.search("recorded acceptance denominator follow-up","PERSONAL_LOAN",8);
            assertTrue(measurement.stream().anyMatch(h->h.path("quote").asText().contains("customers with a qualifying recorded selection")));
            var saved=index.documents().stream().filter(d->d.path("name").asText().equals("a2a-personal-loan-policy.md")).findFirst().orElseThrow();
            String evidence=saved.path("hash").asText()+":0";
            Files.writeString(folder.resolve("a2a-personal-loan-policy.md"),"\nNew evidence review pending.\n",StandardOpenOption.APPEND);
            assertEquals("STALE_POLICY",assertThrows(Fault.class,()->index.validateEvidence("PERSONAL_LOAN",List.of(evidence))).code);
        }
    }
}
