package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import static com.lending.ai.Json.*;

/** Analyst-only exact-preview confirmation and receipt reconciliation through the existing publication transaction. */
public final class PublicationService {
    private final Store store;private final WorkflowService workflows;private final EngineGateway engine;
    public PublicationService(Store store,WorkflowService workflows,EngineGateway engine){this.store=store;this.workflows=workflows;this.engine=engine;}
    public JsonNode preview(String workflow,String analyst,JsonNode body){
        fields(body,"candidateId","note");var w=workflows.get(workflow,analyst).data();
        require(w.path("state").asText().equals("COMPLETED"),"REVIEW_REQUIRED","Complete analysis and reflection before publishing");
        workflows.validateEvidence(w);
        String candidate=text(body,"candidateId"),note=text(body,"note").trim();require(note.length()>=10&&note.length()<=1000,"REVIEW_NOTE","Review note must contain 10-1000 characters");
        JsonNode evaluation=null;for(var e:w.path("evaluations"))if(e.path("candidateId").asText().equals(candidate)&&e.path("phase").asText().equals("VALIDATION")&&e.path("status").asText().equals("COMPLETED"))evaluation=e;
        require(evaluation!=null,"UNTESTED_CANDIDATE","A completed final-validation run is required");
        var run=engine.run(evaluation.path("runId").asText());var current=engine.baseline(run.path("draft").path("id").asText());
        require(current.path("version").asInt()==run.path("draft").path("version").asInt(),"STALE_DRAFT","Draft changed after validation");
        var baseline=(ObjectNode)w.path("baseline");var config=ParameterRegistry.configuration(run.path("draft"));
        new ParameterRegistry().validatePatch(baseline,config,WorkflowService.strings(w.path("allowedParameterIds")),WorkflowService.strings(w.path("lockedStages")));
        require(hash(config).equals(candidate),"CANDIDATE_HASH","Tested configuration differs from the selected candidate");
        var preview=obj("workflowId",workflow,"candidateId",candidate,"runId",run.path("id"),"draftVersion",run.path("draft").path("version"),"configuration",config,
            "changedPaths",ParameterRegistry.differences(baseline,config),"scopeHash",w.path("scopeHash"),"scopeRevision",w.path("revision"),
            "prediction",evaluation.path("prediction"),"note",note,"analyst",analyst,"expiresAt",Instant.now().plusSeconds(900).toString(),"state","AWAITING_CONFIRMATION");
        String id=id(),hash=hash(preview);preview.put("previewId",id).put("previewHash",hash);store.create("PREVIEW",id,preview);return preview;
    }
    /** The authenticated analyst submits the displayed hash; model output never reaches this method. */
    public synchronized JsonNode confirm(String id,String analyst,JsonNode body){
        fields(body,"previewHash","confirm");require(body.path("confirm").asBoolean(),"CONFIRMATION_REQUIRED","Explicit confirmation required");
        var saved=store.get("PREVIEW",id);var p=(ObjectNode)saved.data().deepCopy();
        if(!p.path("analyst").asText().equals(analyst))throw new Fault(404,"NOT_FOUND","Preview not found");
        require(p.path("previewHash").asText().equals(text(body,"previewHash")),"PREVIEW_HASH","Confirmation must match the reviewed preview");
        var w=workflows.get(p.path("workflowId").asText(),analyst).data();
        if(!p.has("receipt")){
            require(w.path("state").asText().equals("COMPLETED")&&w.path("scopeHash").asText().equals(p.path("scopeHash").asText()),"STALE_PREVIEW","Workflow scope changed");
            if(!p.path("state").asText().equals("COMMITTING"))require(Instant.now().isBefore(Instant.parse(p.path("expiresAt").asText())),"PREVIEW_EXPIRED","Review a fresh publication preview");
            if(!p.path("state").asText().equals("COMMITTING")){workflows.validateEvidence(w);p.put("state","COMMITTING").put("confirmedAt",Instant.now().toString());saved=store.update("PREVIEW",id,saved.version(),p);}
            var receipt=engine.publish(p.path("runId").asText(),p.path("note").asText());
            require(receipt.path("runId").asText().equals(p.path("runId").asText())&&!receipt.path("offerId").asText().isBlank(),"PUBLICATION_RECEIPT","Engine did not return a correlated publication receipt");
            p.set("receipt",receipt);p.put("state","PUBLISHED");store.update("PREVIEW",id,saved.version(),p);
        }
        var receipt=p.path("receipt");var link=obj("publicationId",receipt.path("offerId"),"workflowId",w.path("id"),"analyst",analyst,"preview",p,"prediction",p.path("prediction"),
            "predecessorPublicationId",w.path("predecessorPublicationId"),"sourceMode","DEMO");
        if(store.find("PUBLICATION",receipt.path("offerId").asText()).isEmpty())store.create("PUBLICATION",receipt.path("offerId").asText(),link);
        return p;
    }
}

