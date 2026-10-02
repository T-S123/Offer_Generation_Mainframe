package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import static com.lending.ai.Json.*;

/** Analyst-owned follow-ups return results or declarative blockers; only explicit publication phrases act on reviewed previews. */
public final class ConversationService {
    private final Store store;private final WorkflowService workflows;private final PublicationService publications;private final FeedbackService feedback;
    public ConversationService(Store s,WorkflowService w,PublicationService p,FeedbackService f){store=s;workflows=w;publications=p;feedback=f;}
    public JsonNode create(String analyst,JsonNode body){
        fields(body,"requestId","publicationId","workflowId");String id=hash(List.of(analyst,"conversation",text(body,"requestId")));
        if(body.hasNonNull("publicationId"))feedback.publication(text(body,"publicationId"),analyst);
        if(body.hasNonNull("workflowId"))workflows.get(text(body,"workflowId"),analyst);
        var old=store.find("CONVERSATION",id);if(old.isPresent()){require(old.get().data().path("requestHash").asText().equals(hash(body)),"REQUEST_CONFLICT","Conversation request changed");return view(old.get());}
        return view(store.create("CONVERSATION",id,obj("analyst",analyst,"requestHash",hash(body),"publicationId",body.path("publicationId").asText(""),"workflowId",body.path("workflowId").asText(""),"createdAt",Instant.now().toString(),"messages",List.of())));
    }
    public Store.Saved get(String id,String analyst){var c=store.get("CONVERSATION",id);if(!c.data().path("analyst").asText().equals(analyst))throw new Fault(404,"NOT_FOUND","Conversation not found");return c;}
    private static JsonNode view(Store.Saved c){return obj("id",c.id(),"version",c.version(),"conversation",c.data());}
    public JsonNode view(String id,String analyst){return view(get(id,analyst));}
    /** Retries are bound to exact content; publication is reachable only through an explicit analyst operation or phrase. */
    public synchronized JsonNode message(String id,String analyst,JsonNode body){
        fields(body,"requestId","expectedVersion","text","operation","budget","populationId","validationPopulationId","lockedStages","seed","candidateId","note","sourceMode","autoExecute");
        var saved=get(id,analyst);String request=text(body,"requestId"),key=id+":"+hash(request).substring(0,32);var prior=store.find("CONVERSATION_MESSAGE",key);
        if(prior.isPresent()){require(prior.get().data().path("requestHash").asText().equals(hash(body)),"REQUEST_CONFLICT","Message identity changed");return prior.get().data().path("result");}
        require(saved.version()==body.path("expectedVersion").asInt(),"STALE_VERSION","Reload the conversation before acting");
        String text=text(body,"text");require(text.length()<=6000&&saved.data().path("messages").size()<100,"CONVERSATION_LIMIT","Conversation is too large; create another linked conversation");
        String operation=body.path("operation").asText(classify(text));var c=(ObjectNode)saved.data().deepCopy();JsonNode answer;
        String pub=c.path("publicationId").asText(),workflow=c.path("workflowId").asText();
        switch(operation){
            case "PERFORMANCE"->{require(!pub.isBlank(),"PUBLICATION_REQUIRED","Select a publication from your history");answer=feedback.performance(pub,analyst,body.path("sourceMode").asText("DEMO"));}
            case "EXPLAIN"->{require(!pub.isBlank(),"PUBLICATION_REQUIRED","Select a publication");if(!body.has("budget"))answer=obj("status","BLOCKED","answer","No investigation ran because this request has no authorized model budget.");else{answer=feedback.create(pub,analyst,obj("requestId",request,"text",text,"budget",body.path("budget"),"sourceMode",body.path("sourceMode").asText("DEMO")));c.put("workflowId",answer.path("id").asText());}}
            case "REVISE"->{require(!pub.isBlank(),"PUBLICATION_REQUIRED","Select a publication");if(!body.has("budget")||!body.hasNonNull("populationId")||!body.hasNonNull("validationPopulationId"))answer=obj("status","BLOCKED","answer","No successor ran because discovery, validation or its authorized budget is missing from the request.");else{
                answer=feedback.successor(pub,analyst,obj("requestId",request,"intent",text,"budget",body.path("budget"),"populationId",body.path("populationId"),"validationPopulationId",body.path("validationPopulationId"),"lockedStages",body.has("lockedStages")?body.path("lockedStages"):tree(List.of()),"seed",body.path("seed").asLong(20260918),"autoExecute",body.path("autoExecute").asBoolean(true)));c.put("workflowId",answer.path("id").asText());c.remove("preview");
            }}
            case "PREVIEW"->{require(!workflow.isBlank(),"WORKFLOW_REQUIRED","Select a completed experiment");answer=publications.preview(workflow,analyst,obj("candidateId",text(body,"candidateId"),"note",text(body,"note")));c.set("preview",answer);}
            case "PUBLISH"->{require(c.has("preview"),"PREVIEW_REQUIRED","Review one tested candidate before publishing");var p=c.path("preview");answer=publications.confirm(p.path("previewId").asText(),analyst,obj("previewHash",p.path("previewHash"),"confirm",true));c.put("publicationId",answer.path("receipt").path("offerId").asText());}
            case "STATUS"->{require(!workflow.isBlank(),"WORKFLOW_REQUIRED","Select an experiment or explanation");answer=workflows.view(workflows.get(workflow,analyst));}
            case "CLARIFY"->{require(!workflow.isBlank(),"WORKFLOW_REQUIRED","Select the workflow to clarify");answer=workflows.message(workflow,analyst,obj("expectedVersion",workflows.get(workflow,analyst).version(),"text",text));}
            default->answer=obj("status","BLOCKED","answer","This operation is unsupported. Supported operations are performance, why, revise, status and preview; publication requires an explicitly confirmed preview.");
        }
        ((ArrayNode)c.path("messages")).add(obj("role","analyst","text",text,"operation",operation,"at",Instant.now().toString()));
        String answerRef=store.artifact((Object)answer);((ArrayNode)c.path("messages")).add(obj("role","assistant","artifactRef",answerRef));
        var result=obj("answer",answer,"conversation",view(new Store.Saved(id,saved.version()+1,c)));
        store.updateWithReceipt("CONVERSATION",id,saved.version(),c,"CONVERSATION_MESSAGE",key,obj("requestHash",hash(body),"result",result));return result;
    }
    static String classify(String input){
        String t=input.toLowerCase(Locale.ROOT).trim().replaceAll("[.!?]+$","");
        if(Set.of("publish","push the offer","let's push the offer","good, let's push the offer","publish the offer").contains(t))return "PUBLISH";
        if(t.equals("why")||t.startsWith("why ")||t.contains("explain")||t.contains("reason"))return "EXPLAIN";
        if(t.contains("edit")||t.contains("change")||t.contains("fix")||t.contains("underwriting"))return "REVISE";
        if(t.contains("acceptance")||t.contains("performance")||t.contains("rate"))return "PERFORMANCE";
        if(t.equals("status")||t.contains("results"))return "STATUS";return "UNKNOWN";
    }
}
