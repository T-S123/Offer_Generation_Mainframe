package com.lending.ai;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static com.lending.ai.Json.*;

/** Bounded authenticated JSON transport with explicit failures and no redirect or write retries. */
public final class Http {
    private final URI base;private final String token;private final HttpClient client;
    public Http(String base,String token){this.base=URI.create(base.endsWith("/")?base:base+"/");this.token=token;client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();}
    public JsonNode call(String method,String path,Object data){return call(method,path,data,null);}
    public JsonNode call(String method,String path,Object data,String key){
        require(!path.startsWith("/")&&!path.contains("..")&&!path.contains("://"),"INVALID_PATH","Relative resource path required");
        try{
            var b=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(120)).header("Authorization","Bearer "+token).header("Content-Type","application/json");
            if(key!=null)b.header("Idempotency-Key",key);
            var result=client.send(b.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(write(data))).build(),HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;try(var stream=result.body()){bytes=stream.readNBytes(4*1024*1024+1);}
            if(bytes.length>4*1024*1024)throw new Fault(503,"RESPONSE_LIMIT","Service response exceeded limit");
            var body=read(new String(bytes,StandardCharsets.UTF_8));
            if(result.statusCode()>=400)throw new Fault(result.statusCode(),body.path("code").asText("DEPENDENCY_ERROR"),body.path("error").asText("Service request failed"));
            return body;
        }catch(Fault e){throw e;}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new Fault(503,"DEPENDENCY_UNCERTAIN","Service response unavailable; reconcile writes before retrying");}
    }
    public static JsonNode body(HttpExchange x)throws java.io.IOException{
        require(Optional.ofNullable(x.getRequestHeaders().getFirst("Content-Type")).orElse("").startsWith("application/json"),"CONTENT_TYPE","JSON required");
        var bytes=x.getRequestBody().readNBytes(1024*1024+1);require(bytes.length<=1024*1024,"BODY_LIMIT","Request too large");return read(new String(bytes,StandardCharsets.UTF_8));
    }
    public static void send(HttpExchange x,int status,Object value)throws java.io.IOException{
        byte[] bytes=write(value).getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.getResponseHeaders().set("Cache-Control","no-store");
        x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);
    }
    public static Map<String,String> query(String raw){var q=new HashMap<String,String>();if(raw!=null)for(String field:raw.split("&")){var p=field.split("=",2);q.put(URLDecoder.decode(p[0],StandardCharsets.UTF_8),p.length==2?URLDecoder.decode(p[1],StandardCharsets.UTF_8):"");}return q;}
}
