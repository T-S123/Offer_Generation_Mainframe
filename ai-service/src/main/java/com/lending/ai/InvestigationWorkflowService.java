package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import static com.lending.ai.Json.*;
import static com.lending.ai.Contracts.*;

/** Feedback owns an independent read-only queue and a durable six-exchange allowance. */
public final class InvestigationWorkflowService extends WorkflowRuntime {
    private Consumer<String> execution=id->{throw new Fault(503,"INVESTIGATION_UNAVAILABLE","Investigation service unavailable");};
    public InvestigationWorkflowService(Store store,Map<Role,A2AClient> agents){super(store,agents,Network.FEEDBACK);}
    void executor(Consumer<String> execution){this.execution=execution;}
    protected String initialState(){return "FEEDBACK_PENDING";}
    protected Set<String> activeStates(){return Set.of("FEEDBACK_PENDING");}
    protected void executeState(String id,String state){if(state.equals("FEEDBACK_PENDING"))execution.accept(id);}
    public JsonNode create(JsonNode body,String analyst){throw new Fault(422,"PUBLICATION_REQUIRED","Start an investigation from a published offer");}
    /** Continues a completed briefing with a new analyst turn or resumes an agent's pending question. */
    @Override public synchronized JsonNode message(String id,String analyst,JsonNode body) {
        var saved = get(id,analyst);
        var current = saved.data();
        if (!current.path("briefing").asBoolean() || body.has("questionId")) return super.message(id,analyst,body);
        fields(body,"expectedVersion","text");
        String text = text(body,"text");
        int version = body.path("expectedVersion").asInt();
        for (var message : current.path("messages")) {
            if (message.path("requestVersion").asInt(-1) == version) {
                require(message.path("text").asText().equals(text),"MESSAGE_CONFLICT","This turn already has different text");
                return view(saved);
            }
        }
        require(saved.version() == version,"STALE_VERSION","Reload the latest briefing before sending");
        require(current.path("state").asText().equals("COMPLETED"),"WRONG_STATE","Wait for the current reply");
        require(text.length() <= 6000,"MESSAGE_LIMIT","Message too long");
        int used = SimulationConversation.rounds(current);
        require(used < 6,"QUESTION_LIMIT","This briefing has used six exchanges");
        var next = (ObjectNode) current.deepCopy();
        next.put("clarificationRounds",used+1).put("intent",text)
            .put("revision",current.path("revision").asInt()+1).put("state","FEEDBACK_PENDING");
        next.remove(List.of("briefingNews","briefingTrends","briefingProjectionSegments","completedAt","error"));
        next.withArray("messages").add(obj("role","analyst","text",text,"requestVersion",version,"at",Instant.now().toString()));
        return view(store.update("WORKFLOW",id,saved.version(),next));
    }
    protected void revised(ObjectNode workflow){
        if(!workflow.has("measurementVersion"))return;
        workflow.remove(List.of("resolvedScope","customerScope","measurement","customerTrace","plan","answer"));
        workflow.put("publicationId",workflow.path("selectedPublicationId").asText()).put("customerReference",workflow.path("requestedCustomerReference").asText());
    }
    protected JsonNode executionContext(JsonNode workflow){return obj("network","FEEDBACK","interaction",workflow.path("briefing").asBoolean()?"BUSINESS_BRIEFING":"MATERIAL_DECISIONS_ONLY","roundsRemaining",Math.max(0,6-SimulationConversation.rounds(workflow)),"budget",workflow.path("budget"),"usage",store.budget(workflow.path("id").asText()),
        "authority","Read recorded evidence and explain it. No simulation, rule changes, forecast rewriting or publication.",
        "reporting","Keep source, target, cohort and maturity separate. Computed metrics are authoritative; unsupported explanations are hypotheses.");}
}
