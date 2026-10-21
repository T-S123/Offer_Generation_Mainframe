package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.Task;
import java.util.*;
import static com.lending.ai.Json.*;
import static com.lending.ai.Contracts.*;

/** A2A client validates task serialization and resumes input-required tasks using their original task/context IDs. */
public final class A2AClient {
    private final Http http;
    public A2AClient(String url,String token){http=new Http(url,token);}
    public JsonNode send(Request request){
        return send(request,null);
    }
    public JsonNode send(Request request,String taskId){
        var message=obj("messageId",request.requestId(),"role","ROLE_USER","contextId",request.workflowId(),"parts",List.of(obj("mediaType","application/json","data",request)));
        if(taskId!=null)message.put("taskId",taskId);
        return result(http.call("POST","a2a",obj("jsonrpc","2.0","id",request.requestId(),"method","SendMessage","params",obj("message",message)))).path("task");
    }
    public JsonNode get(String taskId){var result=result(http.call("POST","a2a",obj("jsonrpc","2.0","id",id(),"method","GetTask","params",obj("id",taskId))));validateTask(result);return result;}
    public void cancel(String taskId){result(http.call("POST","a2a",obj("jsonrpc","2.0","id",id(),"method","CancelTask","params",obj("id",taskId))));}
    private JsonNode result(JsonNode response){if(response.has("error"))throw new Fault(503,response.path("error").path("data").path("code").asText("A2A_ERROR"),response.path("error").path("message").asText());return response.path("result");}
    public static void validateTask(JsonNode task){try{JsonUtil.fromJson(write(task),Task.class);}catch(Exception e){throw new Fault(503,"INVALID_A2A_TASK","Invalid remote task");}}
}
