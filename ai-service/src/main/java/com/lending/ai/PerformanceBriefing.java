package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.lending.ai.Contracts.*;
import static com.lending.ai.Json.*;

/** Business chat freezes publication comparisons and augments follow-ups with recorded trends and public releases. */
final class PerformanceBriefing {
    private static final int PUBLICATION_LIMIT = 20;
    private final Store store;
    private final InvestigationWorkflowService workflows;
    private final PerformanceService performance;
    private final DemoCohortEvidence cohorts;
    private final Diagnostics diagnostics;
    private final EngineGateway engine;
    private final EconomicNews news = new EconomicNews();

    PerformanceBriefing(Store store, InvestigationWorkflowService workflows, PerformanceService performance,
                        DemoCohortEvidence cohorts, Diagnostics diagnostics, EngineGateway engine) {
        this.store = store;
        this.workflows = workflows;
        this.performance = performance;
        this.cohorts = cohorts;
        this.diagnostics = diagnostics;
        this.engine = engine;
    }

    synchronized JsonNode start(String analyst, JsonNode request) {
        fields(request,"requestId");
        String key = text(request,"requestId");
        require(key.matches("[A-Za-z0-9._:-]{1,80}"),"REQUEST_LIMIT","Valid briefing request ID required");
        String id = hash(List.of(analyst,"performance-briefing",key));
        var previous = store.find("WORKFLOW",id);
        if (previous.isPresent()) return workflows.view(previous.get());
        var budget = new Store.Budget(4,40,800000,new BigDecimal("10"),Instant.now().plus(Duration.ofMinutes(30)).toString(),
            1,0,2,10,0,0);
        var workflow = obj("id",id,"analyst",analyst,"kind","FEEDBACK","network","FEEDBACK","briefing",true,
            "intent","Summarize how active AI publications compare with their saved eligibility and acceptance projections.",
            "state","FEEDBACK_PENDING","revision",1,"createdAt",Instant.now().toString(),"budget",budget,
            "messages",List.of(),"answers",List.of(),"steps",obj(),"evaluations",List.of(),"clarificationRounds",0);
        store.budget(id,budget);
        return workflows.view(store.create("WORKFLOW",id,workflow));
    }

    void execute(String id) {
        var workflow = workflows.current(id);
        String analyst = workflow.path("analyst").asText();
        if (!workflow.has("briefingEvidence")) {
            var evidence = capture(analyst);
            workflows.mutate(id,value -> value.set("briefingEvidence",evidence));
        }
        workflow = workflows.current(id);
        var evidence = workflow.path("briefingEvidence");
        if (workflow.path("revision").asInt() == 1 && evidence.path("offers").isEmpty()) {
            complete(id,"There are no active AI publications to compare. Publish a tested offer to start tracking its performance.",null);
            return;
        }
        var input = obj("mode","BUSINESS_BRIEFING","stage","BRIEFING","question",workflow.path("intent"),"evidence",evidence);
        if (workflow.path("revision").asInt() > 1) {
            input.put("stage","FOLLOW_UP");
            var scope = workflows.autonomousAgent(id,Role.ORCHESTRATOR,"briefing-scope",input);
            if (workflows.stopIfUnresolved(id,scope)) return;
            var plan = scope.decision().investigationPlan();
            String publication = plan == null ? null : plan.publicationId();
            if (publication != null && !publication.isBlank()) {
                JsonNode row = null;
                for (var offer : evidence.path("offers")) if (publication.equals(offer.path("id").asText())) row = offer;
                require(row != null,"SCOPE_CONFLICT","Select an active publication from this briefing");
                if (!workflow.has("briefingTrends") && !row.hasNonNull("demo") && row.at("/native/E").asInt() > 0) {
                    var trends = diagnostics.collect(publication,"DEMO",10,
                        row.path("version").asInt(),Instant.parse(evidence.path("asOf").asText()));
                    workflows.mutate(id,value -> value.set("briefingTrends",trends));
                }
                String population = row.at("/projection/populationId").asText();
                if (!population.isBlank() && !workflows.current(id).has("briefingProjectionSegments")) {
                    JsonNode composition;
                    try { composition = engine.get("business/populations/" + population + "/segments"); }
                    catch (Fault unavailable) { composition = obj("status","UNAVAILABLE"); }
                    var savedComposition = composition;
                    workflows.mutate(id,value -> value.set("briefingProjectionSegments",savedComposition));
                }
                input.set("projectionComposition",workflows.current(id).path("briefingProjectionSegments"));
                input.set("focusedPublication",row);
                input.set("recordedProfileTrends",workflows.current(id).path("briefingTrends"));
            }
            if (!workflows.current(id).has("briefingNews")) {
                var releases = news.search(workflow.path("intent").asText());
                workflows.mutate(id,value -> value.set("briefingNews",releases));
            }
            input.set("news",workflows.current(id).path("briefingNews"));
            var research = workflows.autonomousAgent(id,Role.RESEARCH,"briefing-research",input);
            if (workflows.stopIfUnresolved(id,research)) return;
            input.set("research",tree(research.decision()));
        }
        var analysis = workflows.autonomousAgent(id,Role.ANALYZER,"briefing-analysis",input);
        if (workflows.stopIfUnresolved(id,analysis)) return;
        complete(id,analysis.decision().summary(),analysis.decision().planningAssessment());
    }

    private void complete(String id, String text, PlanningAssessment assessment) {
        workflows.mutate(id,value -> {
            var message = obj("role","assistant","text",text,"at",Instant.now().toString());
            if (assessment != null) message.put("evidenceConfidence",assessment.confidence());
            if (value.has("briefingNews")) message.set("news",value.path("briefingNews"));
            value.withArray("messages").add(message);
            value.put("state","COMPLETED").put("completedAt",Instant.now().toString());
        });
    }

    private JsonNode capture(String analyst) {
        var refresh = performance.refresh();
        Instant cutoff = Instant.now();
        LocalDate today = LocalDate.ofInstant(cutoff,ZoneOffset.UTC);
        var active = new HashMap<String,Integer>();
        for (var offer : engine.get("business/catalog").path("offers")) {
            var data = offer.path("data");
            if (data.path("active").asBoolean() && !today.isBefore(LocalDate.parse(data.path("startsOn").asText())) &&
                !today.isAfter(LocalDate.parse(data.path("endsOn").asText()))) {
                active.put(offer.path("id").asText(),offer.path("version").asInt());
            }
        }
        var demo = cohorts.latestByPublication(analyst);
        var publications = performance.publications(analyst).stream()
            .filter(row -> active.getOrDefault(row.path("id").asText(),0) == row.path("offerVersion").asInt())
            .sorted(Comparator.comparing((JsonNode row) -> row.path("projectedAt").asText()).reversed())
            .toList();
        var offers = new ArrayList<JsonNode>();
        for (var row : publications.stream().limit(PUBLICATION_LIMIT).toList()) {
            String id = row.path("id").asText();
            var publication = performance.publication(id,analyst);
            int version = PerformanceService.version(publication);
            var prediction = publication.path("prediction");
            var recorded = performance.briefingObservation(id,version,"OBSERVED_REAL",cutoff);
            var cohort = demo.get(id);
            var nativeOutcome = cohort == null ? performance.briefingObservation(id,version,"DEMO",cutoff) : tree(null);
            var result = obj("id",id,"name",row.path("name"),"version",version,"product",row.path("product"),
                "projection",obj("metricId","projection:" + id,"kind",prediction.path("predictionKind"),
                    "scope",prediction.path("scope"),"populationId",prediction.path("populationId"),"createdAt",prediction.path("generatedAt"),
                    "acceptancePct",prediction.path("estimatePct"),"eligibilityPct",performance.originalEligibility(publication),
                    "assessed",prediction.path("assessedCount"),"eligible",prediction.path("eligibleCount")),
                "recorded",recorded,"native",nativeOutcome);
            if (cohort != null) {
                var counts = cohort.path("outcomes");
                result.set("demo",obj("metricId","demo:" + hash(List.of(cohort.path("cohortId"),id)),
                    "cohortId",cohort.path("cohortId"),"source","PRESENTER_SYNTHETIC","scope","OFFER_FAMILY",
                    "unresolved",cohort.path("unresolved"),"assessedCount",counts.path("assessed"),
                    "E",counts.path("eligible"),"Y",counts.path("selected"),
                    "eligibilityPct",rate(counts,"eligible","assessed"),"acceptanceAmongEligiblePct",rate(counts,"selected","eligible"),
                    "segments",counts.path("segments")));
            }
            offers.add(result);
        }
        return obj("asOf",cutoff.toString(),"totalActive",publications.size(),"offers",offers,"refresh",refresh,
            "comparisonBasis","Saved simulation projections and recorded cohorts describe different populations and sometimes different offer terms. Differences are descriptive. Segment associations and outside context do not establish causation.");
    }

    private static Double rate(JsonNode counts, String numerator, String denominator) {
        int total = counts.path(denominator).asInt();
        return total == 0 ? null : 100.0 * counts.path(numerator).asInt() / total;
    }
}
