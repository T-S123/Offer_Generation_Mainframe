package com.lending.ai;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.lending.ai.Json.*;
import static com.lending.ai.Contracts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies typed citations and bounded repairs reject invented IDs, plus MCP argument and outcome maturity boundaries. */
class EvidenceBoundaryTest {
    @Test void approvedRangeCitationListsAreAcceptedButMetricIdsStayTyped(){
        var input=obj("scope",obj("ranges",List.of(obj("evidenceIds",List.of("approved:0")))),"runId","run-one");
        var valid=claim("approved:0","run-one");
        assertDoesNotThrow(()->AgentRunner.validateClaims(valid,input));
        assertThrows(Fault.class,()->AgentRunner.validateClaims(claim("run-one","run-one"),input));
        assertThrows(Fault.class,()->AgentRunner.validateClaims(claim("invented-policy","run-one"),input));
    }
    @Test void misplacedRunCitationIsRepairedInternallyWithinReservedCalls(){
        var calls=new java.util.concurrent.atomic.AtomicInteger();var reservations=new ArrayList<String>();
        AgentRunner.Tools tools=new AgentRunner.Tools(){
            public com.fasterxml.jackson.databind.JsonNode descriptors(Role role){return tree(List.of());}
            public com.fasterxml.jackson.databind.JsonNode call(Role role,Request request,ToolCall tool){throw new AssertionError("No tool requested");}
            public void reserve(Request request,String id,long tokens){reservations.add(id);}
        };
        ModelClient model=(instructions,input,schema)->{
            int count=calls.incrementAndGet();
            assertEquals("run-one",input.at("/citationContract/metricIds/0").asText());
            assertTrue(input.at("/citationContract/evidenceIds").isEmpty());
            if(count==1)return new ModelClient.Reply(tree(claim("run-one",null)),"test",10,10);
            assertTrue(input.has("citationRepair"));
            return new ModelClient.Reply(tree(claim(null,"run-one")),"test",10,10);
        };
        var result=new AgentRunner(Role.ANALYZER,model,tools).run(request(),()->false);
        assertEquals("READY",result.decision().status());assertEquals(2,calls.get());assertEquals(2,new HashSet<>(reservations).size());
    }
    @Test void rejectedOutputCannotMakeInventedIdsTrustedOnRepair(){
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        ModelClient model=(instructions,input,schema)->{
            calls.incrementAndGet();
            assertFalse(input.path("citationContract").toString().contains("invented"));
            return new ModelClient.Reply(tree(claim(null,"invented")),"test",10,10);
        };
        assertEquals("UNSUPPORTED_CLAIM",assertThrows(Fault.class,()->new AgentRunner(Role.ANALYZER,model,A2AProtocolTest.tools()).run(request(),()->false)).code);
        assertEquals(2,calls.get(),"One internal correction only");
    }
    static Request request(){return new Request("1.0.0","citation-request","workflow","report",1,java.time.Instant.now().plusSeconds(60).toString(),obj("runId","run-one"));}
    static Decision claim(String evidence,String metric){return new Decision("READY","Summary",null,List.of(),List.of(),List.of(),List.of(new Claim("SIMULATED","Computed result",evidence==null?List.of():List.of(evidence),metric==null?List.of():List.of(metric))),null);}
    @Test void factualClaimsMustReferenceSuppliedMetrics(){
        var decision=new Decision("READY","Summary",null,List.of(),List.of(),List.of(),List.of(new Claim("SIMULATED","80 percent",List.of(),List.of("run-one"))),null);
        assertThrows(Fault.class,()->AgentRunner.validateClaims(decision,obj()));
        assertDoesNotThrow(()->AgentRunner.validateClaims(decision,obj("runId","run-one")));
    }
    @Test void toolArgumentsAreValidatedLocally(){
        var schema=obj("type","object","properties",obj("query",obj("type","string","maxLength",20),"limit",obj("type","integer","minimum",1,"maximum",5)),"required",List.of("query"),"additionalProperties",false);
        assertDoesNotThrow(()->ToolSchema.validate(schema,obj("query","policy","limit",3)));
        assertThrows(Fault.class,()->ToolSchema.validate(schema,obj("query","policy","limit",9)));
        assertThrows(Fault.class,()->ToolSchema.validate(schema,obj("query","policy","command","delete")));
        assertThrows(Fault.class,()->ToolSchema.validate(obj("type","object","$ref","remote"),obj()));
    }
    @Test void provisionalMetricsCannotBecomeMatureForecastErrors(){
        try(var store=new FoundationTest().store()){
            var observed=obj("scope","OFFER_FAMILY","sourceMode","OBSERVED_REAL","coverage","OPERATIONAL_SOURCE_RECONCILED","maturity","PROVISIONAL","acceptanceAmongEligiblePct",40);
            var result=new OutcomeService(store,null,14).compare(obj("scope","OFFER_FAMILY","sourceMode","OBSERVED_REAL","predictionKind","CALIBRATED_SELECTION","estimatePct",80),observed);
            assertEquals("PROVISIONAL",result.path("comparability").asText());assertTrue(result.path("gapPercentagePoints").isNull());
        }
    }
}
