package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import static com.lending.ai.Contracts.*;
import static com.lending.ai.Json.*;

/** Network 1's durable clarification allowance and model-assessed planning gate; questions are written by agents. */
final class SimulationConversation {
    static final int MAX_ROUNDS = 3;
    static final double READY_CONFIDENCE = 0.9;
    private SimulationConversation() {}

    /** Counts exposed rounds, including an unanswered round and legacy workflows, without resetting on revisions. */
    static int rounds(JsonNode workflow) {
        return Math.max(workflow.path("clarificationRounds").asInt(),
            workflow.path("answers").size() + (workflow.hasNonNull("pendingQuestion") ? 1 : 0));
    }

    static JsonNode context(JsonNode workflow) {
        int used = rounds(workflow);
        return obj("roundsUsed", used, "maxRounds", MAX_ROUNDS, "roundsRemaining", Math.max(0, MAX_ROUNDS-used),
            "readinessThreshold", READY_CONFIDENCE,
            "confidenceMeaning", "Model assessment of how well the task is specified, not a calibrated probability or financial confidence interval",
            "afterLimit", "Resolve remaining uncertainty with conservative assumptions inside the explicit request, locks and approved policy. Record every assumption. Never invent facts or relax hard constraints.");
    }

    /** Rejects a fourth question and premature READY decisions; the runner gives the model a bounded repair opportunity. */
    static void validate(Decision decision, Request request, Role role) {
        var payload = request.payload();
        var allowance = payload.at("/executionContext/conversation");
        if (decision.status().equals("NEEDS_INPUT") && allowance.has("roundsRemaining"))
            require(allowance.path("roundsRemaining").asInt()>0, "CLARIFICATION_EXHAUSTED",
                "All three clarification rounds have been used. Resolve remaining uncertainty conservatively and list the assumptions; do not ask another question.");
        boolean planning = role==Role.ORCHESTRATOR && payload.path("stage").asText().equals("SCOPE_INTERPRETATION")
            || role==Role.REFLECTION && payload.path("reviewType").asText().equals("PLAN");
        if (planning && decision.status().equals("READY"))
            require(ready(decision.planningAssessment()), "UNCLEAR_SIMULATION_PLAN",
                "READY requires a narrow objective and scope, confidence at least 0.9 and no unresolved decisions. Ask a useful bundled question if rounds remain; otherwise resolve uncertainty conservatively and record assumptions. Policy research is handled by Research.");
    }

    static boolean ready(PlanningAssessment assessment) {
        return assessment!=null && assessment.confidence()>=READY_CONFIDENCE && assessment.unresolved().isEmpty();
    }

    static String instructions() {
        return """
            Network 1 is a conversational research partner. Help turn a broad goal into a narrow, well-defined experiment before any simulations. Prefer useful clarification over silently choosing a user's objective, scope or tradeoff just because a default exists. Broad or underspecified requests should usually produce at least one meaningful exchange; a completely clear request may proceed with zero. The desired 75-80% interaction rate is an evaluation target across realistic requests, never a quota, random trigger or reason to invent uncertainty.
            Read the request, conversation, answeredDecisions, owner facts and approved evidence first. Do not repeat answered decisions or ask for retrievable facts, supplied budgets, permission to continue, policy reapproval or routine optimizer points. Missing causal evidence and small-sample uncertainty remain reporting limitations.
            Use NEEDS_INPUT for useful unresolved decisions about the goal, allowed changes, bounds, tradeoffs, next experiment or interpretation. Write the question yourself from this request. Bundle related gaps into one concise numbered prompt, usually 2-4 questions when several gaps exist; do not stretch one gap into several rounds. Set a stable decisionKey for the bundle and explain why the answers matter. choices may be empty for a multi-question free-text reply, or contain 2-4 useful complete answers. Other statuses have question=null and clarification=null.
            executionContext.conversation gives the TOTAL remaining rounds across planning, execution, review and follow-ups. Never enter NEEDS_INPUT after three exposed rounds. After the third answer, use conservative assumptions to resolve remaining uncertainty within explicit constraints and approved bounds. Populate assumptions with those choices, not invented evidence. If no lawful in-scope experiment exists, return BLOCKED with the constraint; assumptions cannot unlock fields, override an answer, fabricate financial facts or increase a budget.
            For READY at SCOPE_INTERPRETATION and PLAN review, supply planningAssessment: the resolved objective, exact scopeSummary, confidence from 0 to 1 and unresolved decisions. Confidence measures task clarity, not economic outcomes. READY needs at least 0.9 and no unresolved decisions. Early scope interpretation can delegate numerical policy lookup to Research; PLAN review must check the actual supported ranges. Do not raise confidence merely to bypass the gate.
            Carry all answers and assumptions forward. A partial answer resolves only the parts actually answered: a later bundle may ask about the remaining gaps, without repeating resolved parts. Keep assumptions explicit in the final explanation. Use REVISE for an internal repair. A follow-up answer that only clarifies an unexecuted plan continues planning; it does not start another experiment.
            At RESULT_DISCUSSION, answer the user's message using saved results and conversation. This stage cannot run simulations or change the completed experiment. Explain a proposed refinement as a separate future experiment. Return READY for an answer; ask a bundled NEEDS_INPUT question only if necessary and rounds remain. Do not put new questions into READY prose to evade the three-round limit.
            """;
    }
}
