package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.Consumer;
import static com.lending.ai.Json.*;
import static com.lending.ai.Contracts.*;

/** Feedback owns a separate queue and agent map; it cannot generate candidates, run trials or publish. */
public final class InvestigationWorkflowService extends WorkflowRuntime {
    private Consumer<String> execution=id->{throw new Fault(503,"INVESTIGATION_UNAVAILABLE","Investigation service unavailable");};
    public InvestigationWorkflowService(Store store,Map<Role,A2AClient> agents){super(store,agents,Network.FEEDBACK);}
    void executor(Consumer<String> execution){this.execution=execution;}
    protected String initialState(){return "FEEDBACK_PENDING";}
    protected Set<String> activeStates(){return Set.of("FEEDBACK_PENDING");}
    protected void executeState(String id,String state){if(state.equals("FEEDBACK_PENDING"))execution.accept(id);}
    public JsonNode create(JsonNode body,String analyst){throw new Fault(422,"PUBLICATION_REQUIRED","Start an investigation from a published offer");}
    protected void revised(ObjectNode workflow){
        if(!workflow.has("measurementVersion"))return;
        workflow.remove(List.of("resolvedScope","customerScope","measurement","customerTrace","plan","answer"));
        workflow.put("publicationId",workflow.path("selectedPublicationId").asText()).put("customerReference",workflow.path("requestedCustomerReference").asText());
    }
    protected JsonNode executionContext(JsonNode workflow){return obj("network","FEEDBACK","interaction","MATERIAL_DECISIONS_ONLY","budget",workflow.path("budget"),"usage",store.budget(workflow.path("id").asText()),
        "authority","Read recorded evidence and explain it. No simulation, rule changes, forecast rewriting or publication.",
        "reporting","Keep source, target, cohort and maturity separate. Computed metrics are authoritative; unsupported explanations are hypotheses.");}
}
