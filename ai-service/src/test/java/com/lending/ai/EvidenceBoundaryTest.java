package com.lending.ai;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.lending.ai.Json.*;
import static com.lending.ai.Contracts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Rejects unsupported factual claims and invalid MCP arguments before they can become trusted output or requests. */
class EvidenceBoundaryTest {
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
