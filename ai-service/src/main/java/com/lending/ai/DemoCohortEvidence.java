package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.lending.ai.Json.*;

/** Stores Presenter summaries whose size and publication counts match the completed owner batch. */
final class DemoCohortEvidence {
    private final Store store;
    private final EngineGateway engine;
    private final PerformanceService performance;

    DemoCohortEvidence(Store store, EngineGateway engine, PerformanceService performance) {
        this.store = store;
        this.engine = engine;
        this.performance = performance;
    }

    synchronized JsonNode record(String analyst, JsonNode body) {
        fields(body,"cohortId","size","unresolved","createdAt","offers");
        String id = text(body,"cohortId");
        require(id.matches("DEMO-[a-f0-9]{32}"),"DEMO_COHORT","A tagged Presenter cohort is required");
        var job = engine.get("simulations/" + id);
        int size = count(body,"size",10000);
        require(size > 0 && job.path("status").asText().equals("COMPLETED") && job.path("count").asInt() == size &&
            job.path("createdAt").equals(body.path("createdAt")),
            "DEMO_COHORT","The tagged customer batch must be complete");
        int unresolved = count(body,"unresolved",size);
        require(body.path("offers").isArray() && body.path("offers").size() <= 100,
            "DEMO_COHORT","Expected a bounded list of publication summaries");
        var ids = new HashSet<String>();
        for (var row : body.path("offers")) {
            fields(row,"publicationId","offerVersion","assessed","eligible","selected","segments");
            String publication = text(row,"publicationId");
            require(ids.add(publication) && row.path("offerVersion").asInt() ==
                PerformanceService.version(performance.publication(publication,analyst)),
                "DEMO_COHORT","Each publication must match its recorded catalog version");
            int assessed = count(row,"assessed",size);
            require(assessed + unresolved == size,"DEMO_COHORT","Assessment coverage must account for the entire batch");
            int eligible = count(row,"eligible",assessed);
            count(row,"selected",eligible);
            require(row.path("segments").isArray() && row.path("segments").size() <= 12,
                "DEMO_COHORT","Expected supported aggregate segments");
            for (var cell : row.path("segments")) {
                fields(cell,"dimension","band","assessed","eligible","selected");
                require(Set.of("Debt-to-income","Credit utilization","Credit score").contains(text(cell,"dimension")) &&
                    text(cell,"band").length() <= 30,"DEMO_COHORT","Unknown cohort segment");
                int support = count(cell,"assessed",assessed);
                require(support >= 10,"DEMO_COHORT","Segment needs at least ten assessed profiles");
                count(cell,"selected",count(cell,"eligible",support));
            }
        }
        String key = hash(List.of(analyst,id));
        var existing = store.find("DEMO_COHORT",key);
        if (existing.isPresent()) {
            require(existing.get().data().path("report").equals(body),"DEMO_COHORT_CONFLICT","Completed cohort evidence is immutable");
        } else {
            store.create("DEMO_COHORT",key,obj("analyst",analyst,"report",body));
        }
        return obj("cohortId",id,"status","RECORDED");
    }

    Map<String,JsonNode> latestByPublication(String analyst) {
        var latest = new HashMap<String,JsonNode>();
        for (var entry : new RecordedEvidence(store).all("DEMO_COHORT")) {
            if (!analyst.equals(entry.path("analyst").asText())) continue;
            var cohort = entry.path("report");
            for (var row : cohort.path("offers")) {
                String id = row.path("publicationId").asText();
                var candidate = obj("cohortId",cohort.path("cohortId"),"createdAt",cohort.path("createdAt"),
                    "size",cohort.path("size"),"unresolved",cohort.path("unresolved"),"outcomes",row);
                latest.merge(id,candidate,(first,second) -> first.path("createdAt").asText()
                    .compareTo(second.path("createdAt").asText()) >= 0 ? first : second);
            }
        }
        return latest;
    }

    private static int count(JsonNode row, String name, int maximum) {
        var value = row.path(name);
        require(value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0 && value.intValue() <= maximum,
            "DEMO_COHORT","Invalid " + name + " count");
        return value.intValue();
    }
}
