package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import static com.lending.ai.Json.*;

/** Supplies simulation constraints and a three-round conversational allowance; defaults resolve remaining gaps after clarification. */
final class AutonomousPolicy {
    private AutonomousPolicy(){}
    static JsonNode context(JsonNode workflow,JsonNode usage) {
        var budget=workflow.path("budget");
        var phases=workflow.has("phaseBudget")?workflow.path("phaseBudget"):budget;
        return obj("version","conversational-simulation-v1","interaction","CONVERSATIONAL_PLANNING","workflowState",workflow.path("state"),"conversation",SimulationConversation.context(workflow),
            "questionPolicy","Resolve retrievable facts first, but clarify meaningful gaps in the user's goal, scope or tradeoffs before choosing defaults. Bundle related questions in each of at most three rounds. A fully specified request may use zero rounds. After the limit, resolve gaps conservatively and list assumptions. Waiting pauses the deadline; answering does not increase spending or trial caps.",
            "routineDefaults",obj("objectives","Clarify an ambiguous goal while rounds remain. Once the goal is resolved, or after the round limit, report both best-tested acceptance and eligibility winners unless the user explicitly requests a priority decision",
                "trialSelection","Use the deterministic optimizer and current phase allocation; do not ask which score point to try",
                "interpretation","State missing causal evidence, small support and synthetic limitations in the result rather than asking permission to report them",
                "authority","Defaults do not override explicit user choices, locks, approved ranges, support, floors or budget"),
            "budget",budget,"used",obj("evaluations",usage.path("evaluations"),"modelCalls",usage.path("modelCalls"),"tokens",usage.path("reservedTokens"),"reservedCostUsd",usage.path("reservedCostUsd")),
            "remaining",obj("evaluations",Math.max(0,budget.path("maxEvaluations").asInt()-usage.path("evaluations").asInt()),
                "modelCalls",Math.max(0,budget.path("maxModelCalls").asInt()-usage.path("modelCalls").asInt()),
                "tokens",Math.max(0,budget.path("maxTokens").asLong()-usage.path("reservedTokens").asLong()),
                "reservedCostUsd",budget.path("maxCostUsd").decimalValue().subtract(usage.path("reservedCostUsd").decimalValue()).max(BigDecimal.ZERO),
                "deadline",budget.path("deadline")),
            "allocation",obj("baseline",1,"exploration",phases.path("exploration"),"refinement",phases.path("refinement"),"finalValidation",phases.path("validation"),
                "semantics","Upper bounds; deduplicate candidates. Java reserves each trial and model call atomically. Do not add allocations to already consumed trials."),
            "ranking",obj("minimumEligible",budget.path("minimumEligible"),"acceptanceWinnerEligibilityFloorPct",budget.path("eligibilityFloor"),
                "eligibilityWinnerAcceptanceFloorPct",budget.path("acceptanceFloor"),"zeroFloorMeaning","No additional cross-metric floor; minimum eligible support still applies",
                "noFeasibleWinner","Return no supported winner for that objective; never relax constraints, invent a winner or request clarification"),
            "reporting",obj("acceptance","100 * simulatedExpectedSelections / eligible; null when eligible is zero",
                "eligibility","100 * eligible / assessed","required","Report assessed, eligible, expected selections, both rates, candidate/run IDs and small-cohort limitations",
                "supportMeaning","An execution and ranking gate, not proof of statistical reliability. Report small samples as limitations, not analyst questions.",
                "claims","Best tested only. Synthetic utility is not calibrated conversion, a global optimum or a causal effect."),
            "validation","Freeze shortlist before evaluating distinct final-validation population; do not tune against validation results",
            "scope","Only analyst-requested fields may vary; all others remain fixed. Explicit stage locks and approved evidence bounds are mandatory.",
            "scoreDefault","After the clarification limit, or if the analyst explicitly delegates the choice, an unspecified credit-score stage uses bureau minimumScore if unlocked. Record this assumption. Never unlock a stage.",
            "conflicts","Return BLOCKED with a declarative reason for incompatible locks, missing approved range evidence or an unsupported requested field",
            "authority","Request and budget authorize automatic simulation only. Publication requires a separate reviewed preview and explicit analyst confirmation.");
    }
}
