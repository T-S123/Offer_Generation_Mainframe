package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.lending.ai.Json.*;

/** Resolves saved workflow ownership and relays compatibility conversation requests to the owning runtime. */
public final class WorkflowRouter {
    private Map<Network,Http> remotes=Map.of();
    void remotes(Map<Network,Http> value){remotes=Map.copyOf(value);}
    private PersonalizationWorkflowService personalization;
    public void personalization(PersonalizationWorkflowService value){personalization=value;}
    private final Store store;private final WorkflowService simulation;private final InvestigationWorkflowService feedback;
    public WorkflowRouter(Store store,WorkflowService simulation,InvestigationWorkflowService feedback){this.store=store;this.simulation=simulation;this.feedback=feedback;}
    public WorkflowRuntime network(Network network){WorkflowRuntime runtime=network==Network.PERSONALIZATION?personalization:network==Network.SIMULATION?simulation:feedback;if(runtime==null)throw new Fault(503,"NETWORK_UNAVAILABLE","Requested network is unavailable");return runtime;}
    public WorkflowRuntime owner(String id){return network(ownerNetwork(id));}
    Network ownerNetwork(String id){return Network.workflow(store.get("WORKFLOW",id).data());}
    public Store.Saved get(String id,String analyst){
        var saved=store.get("WORKFLOW",id);
        if(!saved.data().path("analyst").asText().equals(analyst))throw new Fault(404,"NOT_FOUND","Workflow not found");
        return saved;
    }
    public JsonNode view(Store.Saved saved){return network(Network.workflow(saved.data())).view(saved);}
    JsonNode status(String id,String analyst){
        var saved=get(id,analyst);
        var endpoint=remotes.get(Network.workflow(saved.data()));
        return endpoint==null?view(saved):endpoint.call("GET","api/v1/ai/workflows/"+id,null);
    }
    public JsonNode message(String id,String analyst,JsonNode body){
        var saved=get(id,analyst);
        var endpoint=remotes.get(Network.workflow(saved.data()));
        return endpoint==null?owner(id).message(id,analyst,body):endpoint.call("POST","api/v1/ai/workflows/"+id+"/messages",body);
    }
    public List<Store.Saved> list(String analyst,String after,int limit,Network network){return list(store,analyst,after,limit,network);}
    static List<Store.Saved> list(Store store,String analyst,String after,int limit,Network network){
        require(limit>0&&limit<=100,"PAGE_LIMIT","Use limit 1-100");var result=new ArrayList<Store.Saved>();String cursor=after;
        for(;;){var page=store.list("WORKFLOW",cursor,100);for(var row:page){var data=row.data();if(data.path("analyst").asText().equals(analyst)&&(network==null||Network.workflow(data)==network)){
            result.add(new Store.Saved(row.id(),row.version(),obj("network",Network.workflow(data),"state",data.path("state"),"intent",data.path("intent"),"createdAt",data.path("createdAt"))));if(result.size()==limit)return result;}}
            if(page.size()<100)return result;cursor=page.get(page.size()-1).id();}
    }
}
