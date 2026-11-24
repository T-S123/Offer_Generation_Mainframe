package com.lending.engine.api;

import com.lending.engine.infrastructure.Json;
import com.lending.engine.infrastructure.BankTarget;
import com.lending.engine.domain.Model.Problem;
import com.sun.net.httpserver.HttpExchange;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Set;

/** Forwards JSON over loopback REST to this bank's network API using its analyst credential. */
public final class AiProxy {
    private static final String PREFIX="/api/v1/ai/";
    private static final HttpClient CLIENT=HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private enum Destination {
        SIMULATION("", "AI_BASE_PORT",0),
        FEEDBACK("feedback", "AI_FEEDBACK_BASE_PORT",10),
        PERSONALIZATION("personalization", "AI_PERSONALIZATION_BASE_PORT",20);

        final String directory;
        final String portVariable;
        final int offset;
        Destination(String directory,String portVariable,int offset){
            this.directory=directory;this.portVariable=portVariable;this.offset=offset;
        }

        URI endpoint(String path){
            int base=Integer.parseInt(System.getenv().getOrDefault("AI_BASE_PORT","8100"));
            int port=Integer.parseInt(System.getenv().getOrDefault(portVariable,Integer.toString(base+offset)));
            if(port<1||port>65535)throw new Problem(503,"Invalid port for the "+name()+" REST API");
            return URI.create("http://127.0.0.1:"+port+path);
        }

        String credential(Path root)throws java.io.IOException{
            Path tokenFile=BankTarget.processRuntime(root).resolve("ai").resolve(directory).resolve("analyst-token");
            if(!Files.isRegularFile(tokenFile))throw new Problem(503,"REST credential missing for "+name()+". Initialize the AI services.");
            String token=Files.readString(tokenFile).trim();
            if(token.length()<32)throw new Problem(503,"Invalid REST credential for "+name());
            return token;
        }
    }
    private AiProxy(){}
    /** Older unscoped workflow paths go to the compatibility endpoint for saved-owner lookup. */
    private static Destination destination(URI uri){
        String route=uri.getPath().substring(PREFIX.length());
        String[] parts=route.split("/",-1);
        return switch(parts[0]){
            case "customer-personalization" -> Destination.PERSONALIZATION;
            case "feedback","performance","notices","calibrations","outcomes" -> Destination.FEEDBACK;
            case "publications" -> parts.length==1||parts.length==3&&!parts[2].equals("successors")
                ?Destination.FEEDBACK:Destination.SIMULATION;
            default -> Destination.SIMULATION;
        };
    }
    public static Object route(HttpExchange x)throws Exception{
        URI uri=x.getRequestURI();
        String path=uri.toASCIIString();
        if(!path.startsWith(PREFIX)||uri.getPath().contains(".."))throw new Problem(422,"Invalid AI path");
        if(!Set.of("GET","POST").contains(x.getRequestMethod()))throw new Problem(405,"Unsupported AI method");
        var destination=destination(uri);
        Path root=Path.of(System.getProperty("engine.home",".")).toAbsolutePath().normalize();
        String token=destination.credential(root);
        byte[] body=x.getRequestBody().readNBytes(1048577);if(body.length>1048576)throw new Problem(413,"AI request too large");
        var request=HttpRequest.newBuilder(destination.endpoint(path))
            .timeout(Duration.ofSeconds(120)).header("Authorization","Bearer "+token).header("Content-Type","application/json")
            .method(x.getRequestMethod(),body.length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body)).build();
        try{var response=CLIENT.send(request,HttpResponse.BodyHandlers.ofInputStream());byte[] bytes;try(var stream=response.body()){bytes=stream.readNBytes(4194305);}
            if(bytes.length>4194304)throw new Problem(503,"AI response exceeded limit");
            var result=Json.MAPPER.readTree(bytes);if(response.statusCode()>=400)throw new Problem(response.statusCode(),result.path("error").asText("AI operation failed"));return result;
        }catch(java.io.IOException e){throw new Problem(503,"AI service unavailable; manual Business options remain available");}
    }
}
