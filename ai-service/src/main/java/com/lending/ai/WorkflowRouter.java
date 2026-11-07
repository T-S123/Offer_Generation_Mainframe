package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.lending.ai.Json.*;

/** Compatibility routing resolves immutable ownership across all three networks; it never runs a workflow itself. */
public final class WorkflowRouter {
    private PersonalizationWorkflowService personalization;
    public void personalization(PersonalizationWorkflowService value){personalization=value;}
    private final Store store;private final WorkflowService simulation;private final InvestigationWorkflowService feedback;
    public WorkflowRouter(Store store,WorkflowService simulation,InvestigationWorkflowService feedback){this.store=store;this.simulation=simulation;this.feedback=feedback;}
    public WorkflowRuntime network(Network network){WorkflowRuntime runtime=network==Network.PERSONALIZATION?personalization:network==Network.SIMULATION?simulation:feedback;if(runtime==null)throw new Fault(503,"NETWORK_UNAVAILABLE","Requested network is unavailable");return runtime;}
    public WorkflowRuntime owner(String id){return network(Network.workflow(store.get("WORKFLOW",id).data()));}
    public Store.Saved get(String id,String analyst){return owner(id).get(id,analyst);}
    public JsonNode view(Store.Saved saved){return network(Network.workflow(saved.data())).view(saved);}
    public JsonNode message(String id,String analyst,JsonNode body){return owner(id).message(id,analyst,body);}
    public List<Store.Saved> list(String analyst,String after,int limit,Network network){return list(store,analyst,after,limit,network);}
    static List<Store.Saved> list(Store store,String analyst,String after,int limit,Network network){
        require(limit>0&&limit<=100,"PAGE_LIMIT","Use limit 1-100");var result=new ArrayList<Store.Saved>();String cursor=after;
        for(;;){var page=store.list("WORKFLOW",cursor,100);for(var row:page){var data=row.data();if(data.path("analyst").asText().equals(analyst)&&(network==null||Network.workflow(data)==network)){
            result.add(new Store.Saved(row.id(),row.version(),obj("network",Network.workflow(data),"state",data.path("state"),"intent",data.path("intent"),"createdAt",data.path("createdAt"))));if(result.size()==limit)return result;}}
            if(page.size()<100)return result;cursor=page.get(page.size()-1).id();}
    }
}
