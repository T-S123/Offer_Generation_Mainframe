package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.lending.ai.Json.*;

/** Uses engine-owned APIs for immutable candidates, idempotent run admission and reviewed publication. */
public class EngineGateway {
    protected final Http engine;
    public EngineGateway(String url,String token){engine=new Http(url,token);}
    public JsonNode get(String path){return engine.call("GET",path,null);}
    public JsonNode baseline(String id){return get("business/drafts/"+id);}
    public JsonNode population(String id){for(var p:get("business/populations"))if(p.path("id").asText().equals(id))return p;throw new Fault(404,"POPULATION_MISSING","Simulation population not found");}
    public JsonNode candidate(String workflow,JsonNode source,ObjectNode configuration,String hash){
        String key=workflow+":"+hash.substring(0,32);var request=obj("name","AI "+hash.substring(0,12),"baselineOfferId",source.path("baseline").path("id"),"campaignId",source.path("campaign").path("id"));
        var created=engine.call("POST","business/drafts",request,key);var current=baseline(text(created,"id"));
        for(String field:List.of("baseline","campaign","policy"))require(Json.hash(source.path(field)).equals(Json.hash(current.path(field))),"BASELINE_CHANGED","Catalog or policy changed; begin a new experiment");
        if(Json.hash(ParameterRegistry.configuration(current)).equals(Json.hash(configuration)))return current;
        require(current.path("version").asInt()==1,"CANDIDATE_CHANGED","Candidate draft was edited; automatic execution stopped");
        var rules=(ObjectNode)configuration.path("rules").deepCopy();for(String k:List.of("id","version","createdAt"))rules.set(k,current.path("rules").path(k));
        return engine.call("PUT","business/drafts/"+text(current,"id"),obj("expectedVersion",1,"name",current.path("name"),"offer",configuration.path("offer"),"rules",rules));
    }
    public JsonNode submit(String key,JsonNode draft,String population,long seed){return engine.call("POST","business/runs",obj("draftId",draft.path("id"),"draftVersion",draft.path("version"),"populationId",population,"seed",seed),key);}
    public JsonNode run(String id){return get("business/runs/"+id);}
    public JsonNode publish(String run,String note){return engine.call("POST","business/runs/"+run+"/publish",obj("confirm",true,"note",note));}
    /** Creates an isolated successor from the engine's current published catalog and campaign snapshots. */
    public JsonNode successor(JsonNode publication,String requestKey){
        var receipt=publication.path("preview").path("receipt");
        require(!receipt.path("offerId").asText().isBlank()&&!receipt.path("campaignId").asText().isBlank(),"PUBLICATION_LINEAGE","Catalog receipt is incomplete");
        return engine.call("POST","business/drafts",obj("name","AI successor","baselineOfferId",receipt.path("offerId"),"campaignId",receipt.path("campaignId")),requestKey);
    }
    public JsonNode publications(){return get("business/publications");}
}

