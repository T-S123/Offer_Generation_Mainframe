package com.lending.ai;
import org.junit.jupiter.api.*;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.wrappers.*;
import org.a2aproject.sdk.spec.AgentCard;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.lending.ai.Json.*;
import static com.lending.ai.Contracts.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP contract tests use SDK response decoders and explicit deterministic model doubles. */
class A2AProtocolTest {
    static com.fasterxml.jackson.databind.JsonNode ready(){return tree(new Decision("READY","Checked",null,List.of(),List.of(),List.of(),List.of(),null));}
    static AgentRunner.Tools tools(){return new AgentRunner.Tools(){
        public com.fasterxml.jackson.databind.JsonNode descriptors(Role r){return tree(List.of());}
        public com.fasterxml.jackson.databind.JsonNode call(Role r,Request q,ToolCall t){throw new AssertionError("Unexpected tool");}
        public void reserve(Request r,String id,long tokens){}
    };}
    static Request request(String id){return new Request("1.0.0",id,"workflow","step",1,Instant.now().plusSeconds(60).toString(),obj("mode","test"));}
    static com.fasterxml.jackson.databind.JsonNode send(Request q){return obj("jsonrpc","2.0","id","1","method","SendMessage","params",obj("message",obj("messageId","m1","role","ROLE_USER","parts",List.of(obj("data",q,"mediaType","application/json")))));}
    @Test void sdkDecodesCardSendAndGetAndDuplicateExecutesOnce()throws Exception{
        var calls=new AtomicInteger();var s=new FoundationTest().store();
        var a=new A2AService(Role.ANALYZER,s,new AgentRunner(Role.ANALYZER,(i,p,j)->{calls.incrementAndGet();return new ModelClient.Reply(ready(),"test-double",12,10);},tools()));
        try(s;a;var server=new Server(0,Role.ANALYZER,"tester",Map.of("ORCHESTRATOR","test-key","analyst","analyst-key"),a,s,null,null,null,null,null)){
            a.card("http://127.0.0.1:8100/a2a");server.start();var http=new Http("http://127.0.0.1:"+server.port(),"test-key");
            var card=http.call("GET",".well-known/agent-card.json",null);
            assertEquals("1.0",JsonUtil.fromJson(write(card),AgentCard.class).supportedInterfaces().get(0).protocolVersion());
            var rpc=send(request("request-one"));var sent=http.call("POST","a2a",rpc);assertFalse(sent.has("error"),write(sent));
            assertNotNull(JsonUtil.fromJson(write(sent),SendMessageResponse.class));
            String id=sent.path("result").path("task").path("id").asText();assertFalse(id.isBlank());http.call("POST","a2a",rpc);
            com.fasterxml.jackson.databind.JsonNode fetched=null;
            for(int i=0;i<100;i++){fetched=http.call("POST","a2a",obj("jsonrpc","2.0","id",2,"method","GetTask","params",obj("id",id)));if(fetched.path("result").path("status").path("state").asText().equals("TASK_STATE_COMPLETED"))break;Thread.sleep(10);}
            assertEquals("TASK_STATE_COMPLETED",fetched.path("result").path("status").path("state").asText(),write(fetched));
            assertNotNull(JsonUtil.fromJson(write(fetched),GetTaskResponse.class));assertEquals(1,calls.get());
            assertEquals(-32001,a.rpc(obj("jsonrpc","2.0","id",3,"method","GetTask","params",obj("id",id)),"another-agent").path("error").path("code").asInt());
            assertTrue(http.call("POST","a2a",send(new Request("1.0.0","request-one","workflow","step",1,Instant.now().plusSeconds(60).toString(),obj("different",true)))).has("error"));assertEquals(1,calls.get());
            assertEquals(422,assertThrows(Fault.class,()->new Http("http://127.0.0.1:"+server.port(),"analyst-key").call("POST","a2a",rpc)).status);
        }
    }
    @Test void cancellationWinsAgainstLateModelCompletion()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var s=new FoundationTest().store();
        var a=new A2AService(Role.RESEARCH,s,new AgentRunner(Role.RESEARCH,(i,p,j)->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return new ModelClient.Reply(ready(),"test",1,1);},tools()));
        try(s;a){
            var response=a.rpc(send(request("cancel-me")),"owner");String id=response.path("result").path("task").path("id").asText();assertTrue(entered.await(2,TimeUnit.SECONDS));
            a.rpc(obj("jsonrpc","2.0","id",2,"method","CancelTask","params",obj("id",id)),"owner");release.countDown();Thread.sleep(80);
            assertEquals("TASK_STATE_CANCELED",a.rpc(obj("jsonrpc","2.0","id",3,"method","GetTask","params",obj("id",id)),"owner").path("result").path("status").path("state").asText());
        }finally{release.countDown();}
    }
}
