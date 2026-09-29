package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static com.lending.ai.Json.*;
import static com.lending.ai.Contracts.*;

/** A2A 1.0 JSON-RPC adapter using official SDK types, with durable task ownership and bounded execution. */
public final class A2AService implements AutoCloseable {
    private final Role role;private final Store store;private final AgentRunner runner;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16));
    public A2AService(Role role,Store store,AgentRunner runner){this.role=role;this.store=store;this.runner=runner;
        String cursor="";for(;;){var page=store.list("TASK",cursor,100);for(var t:page){String state=t.data().path("task").path("status").path("state").asText();
            if(Set.of("TASK_STATE_WORKING","TASK_STATE_SUBMITTED").contains(state))finish(t.id(),"TASK_STATE_FAILED",obj("code","INTERRUPTED","message","Agent restarted; use a new bounded attempt"));
        }if(page.size()<100)break;cursor=page.get(page.size()-1).id();}}
    /** Advertises only implemented protocol capabilities; the HTTP principal grants actual permissions. */
    public JsonNode card(String url){
        var n=obj("name",role+" offer simulation agent","description",AgentRunner.instructions(role),"version","1.0.0",
            "supportedInterfaces",List.of(obj("url",url,"protocolBinding","JSONRPC","protocolVersion","1.0")),
            "capabilities",obj("streaming",false,"pushNotifications",false),
            "defaultInputModes",List.of("application/json"),"defaultOutputModes",List.of("application/json"),
            "securitySchemes",obj("serviceBearer",obj("httpAuthSecurityScheme",obj("scheme","bearer","bearerFormat","opaque"))),
            "securityRequirements",List.of(sdk(new org.a2aproject.sdk.spec.SecurityRequirement(Map.of("serviceBearer",List.of())))),
            "skills",List.of(obj("id",role.name().toLowerCase(Locale.ROOT),"name",role.name(),"description","Typed "+role+"Request to "+role+"Result","tags",List.of("simulation","structured"),"inputModes",List.of("application/json"),"outputModes",List.of("application/json"))));
        try{JsonUtil.fromJson(write(n),AgentCard.class);}catch(Exception e){throw new IllegalStateException("Invalid Agent Card",e);}return n;
    }
    private static JsonNode sdk(Object value){try{return read(JsonUtil.toJson(value));}catch(Exception e){throw new IllegalStateException("SDK encoding failed",e);}}
    public JsonNode rpc(JsonNode rpc,String principal){
        var id=rpc.path("id");
        try{
            require(rpc.path("jsonrpc").asText().equals("2.0")&&(id.isTextual()||id.isNumber()),"INVALID_REQUEST","JSON-RPC request identity required");
            String method=text(rpc,"method");var p=rpc.path("params");
            return switch(method){
                case "SendMessage" -> {
                    try{JsonUtil.fromJson(write(rpc),SendMessageRequest.class);}catch(Exception e){throw new Fault(422,"INVALID_PARAMS","Invalid A2A SendMessage");}
                    var message=p.path("message");require(message.path("role").asText().equals("ROLE_USER")&&message.path("parts").size()==1&&message.path("parts").get(0).path("data").isObject(),"INVALID_PARAMS","One structured user data part is required");
                    var request=convert(message.path("parts").get(0).path("data"),Request.class);request.validate();
                    String taskId=hash(List.of(principal,role,request.requestId()));String fingerprint=hash(request);
                    var prior=store.find("TASK",taskId);
                    if(prior.isEmpty()){
                        var task=obj("id",taskId,"contextId",request.workflowId(),"status",obj("state","TASK_STATE_SUBMITTED","timestamp",Instant.now().toString()));
                        try{
                            store.create("TASK",taskId,obj("owner",principal,"fingerprint",fingerprint,"request",request,"task",task));
                            try{worker.execute(()->execute(taskId,request));}catch(RejectedExecutionException e){finish(taskId,"TASK_STATE_REJECTED",obj("code","AGENT_BUSY","message","Agent queue is full"));}
                        }catch(Fault e){if(!e.code.equals("DUPLICATE"))throw e;}
                    }
                    var saved=owned(taskId,principal);require(saved.data().path("fingerprint").asText().equals(fingerprint),"REQUEST_CONFLICT","Request ID was reused with different content");
                    yield obj("jsonrpc","2.0","id",id,"result",obj("task",saved.data().path("task")));
                }
                case "GetTask" -> obj("jsonrpc","2.0","id",id,"result",owned(text(p,"id"),principal).data().path("task"));
                case "CancelTask" -> {
                    String taskId=text(p,"id");var saved=owned(taskId,principal);String state=saved.data().path("task").path("status").path("state").asText();
                    if(TaskState.valueOf(state).isFinal()&&!state.equals("TASK_STATE_CANCELED"))throw new Fault(409,"TASK_NOT_CANCELABLE","Task has already finished");
                    if(!state.equals("TASK_STATE_CANCELED"))finish(taskId,"TASK_STATE_CANCELED",null);
                    yield obj("jsonrpc","2.0","id",id,"result",owned(taskId,principal).data().path("task"));
                }
                default -> obj("jsonrpc","2.0","id",id,"error",obj("code",-32601,"message","Method not supported"));
            };
        }catch(Fault e){return obj("jsonrpc","2.0","id",id,"error",obj("code",e.status==404?-32001:e.code.equals("TASK_NOT_CANCELABLE")?-32002:-32602,"message",e.getMessage(),"data",obj("code",e.code)));}
    }
    private Store.Saved owned(String id,String principal){var saved=store.get("TASK",id);if(!saved.data().path("owner").asText().equals(principal))throw new Fault(404,"NOT_FOUND","Task not found");return saved;}
    private synchronized void execute(String id,Request request){
        if(canceled(id))return;finish(id,"TASK_STATE_WORKING",null);
        try{var result=runner.run(request,()->canceled(id));if(!canceled(id))finish(id,result.decision().status().equals("NEEDS_INPUT")?"TASK_STATE_INPUT_REQUIRED":"TASK_STATE_COMPLETED",tree(result));}
        catch(Fault e){if(!canceled(id))finish(id,"TASK_STATE_FAILED",obj("code",e.code,"message",e.getMessage()));}
        catch(Exception e){if(!canceled(id))finish(id,"TASK_STATE_FAILED",obj("code","AGENT_FAILURE","message","Agent execution failed"));}
    }
    private boolean canceled(String id){return store.get("TASK",id).data().path("task").path("status").path("state").asText().equals("TASK_STATE_CANCELED");}
    private void finish(String id,String state,JsonNode output){
        for(int retry=0;retry<4;retry++){
            var saved=store.get("TASK",id);var data=(ObjectNode)saved.data().deepCopy();var task=(ObjectNode)data.path("task");
            if(task.path("status").path("state").asText().equals("TASK_STATE_CANCELED"))return;
            task.set("status",obj("state",state,"timestamp",Instant.now().toString()));
            if(output!=null){String ref=store.artifact((Object)output);task.set("artifacts",tree(List.of(obj("artifactId",ref,"name",role+" result","parts",List.of(obj("mediaType","application/json","data",output))))));}
            try{JsonUtil.fromJson(write(task),Task.class);store.update("TASK",id,saved.version(),data);return;}
            catch(Fault e){if(!e.code.equals("STALE_VERSION"))throw e;}catch(Exception e){throw new IllegalStateException(e);}
        }
    }
    public void close(){worker.shutdownNow();try{worker.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}

