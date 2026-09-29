package com.lending.engine.api;

import com.lending.engine.infrastructure.Json;
import com.lending.engine.domain.Model.Problem;
import com.sun.net.httpserver.HttpExchange;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;

/** Optional loopback AI interaction proxy; manual terminal operations remain available when AI services are absent. */
public final class AiProxy {
    private AiProxy(){}
    public static Object route(HttpExchange x)throws Exception{
        Path root=Path.of(System.getProperty("engine.home",".")).toAbsolutePath().normalize();
        Path credentials=root.resolve("runtime/ai/credentials.json");
        if(!Files.isRegularFile(credentials))throw new Problem(503,"AI services are not configured. Run scripts/run-ai.ps1.");
        var auth=Json.MAPPER.readTree(Files.readString(credentials));String token=auth.path("analyst").asText();
        if(token.length()<32)throw new Problem(503,"AI analyst credential is missing");
        String path=x.getRequestURI().toASCIIString();
        if(!path.startsWith("/api/v1/ai/")||path.contains(".."))throw new Problem(422,"Invalid AI path");
        if(!java.util.Set.of("GET","POST").contains(x.getRequestMethod()))throw new Problem(405,"Unsupported AI method");
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        byte[] body=x.getRequestBody().readNBytes(1048577);if(body.length>1048576)throw new Problem(413,"AI request too large");
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+Integer.parseInt(System.getenv().getOrDefault("AI_BASE_PORT","8100"))+path))
            .timeout(Duration.ofSeconds(120)).header("Authorization","Bearer "+token).header("Content-Type","application/json")
            .method(x.getRequestMethod(),body.length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body)).build();
        try{var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());byte[] bytes;try(var stream=response.body()){bytes=stream.readNBytes(4194305);}
            if(bytes.length>4194304)throw new Problem(503,"AI response exceeded limit");
            var result=Json.MAPPER.readTree(bytes);if(response.statusCode()>=400)throw new Problem(response.statusCode(),result.path("error").asText("AI operation failed"));return result;
        }catch(java.io.IOException e){throw new Problem(503,"AI service unavailable; manual Business options remain available");}
    }
}
