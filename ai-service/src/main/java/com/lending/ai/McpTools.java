package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.lending.ai.Json.*;

/** Operator-configured read-only MCP connections; discovered tools cannot grant themselves authority. */
public final class McpTools {
    private final JsonNode config;
    public McpTools(Path file){try{config=Files.exists(file)?read(Files.readString(file)):obj("servers",List.of());}catch(Exception e){throw new Fault(503,"MCP_CONFIG","Cannot load MCP configuration");}}
    public List<JsonNode> descriptors(Contracts.Role role){
        var result=new ArrayList<JsonNode>();
        for(var s:config.path("servers"))if(allowed(s,role))for(var t:s.path("tools"))result.add(obj("name",s.path("name").asText()+"."+t.path("name").asText(),"description",t.path("description"),"inputSchema",t.path("inputSchema"),"sideEffect","READ"));
        return result;
    }
    private boolean allowed(JsonNode s,Contracts.Role role){for(var r:s.path("roles"))if(r.asText().equals(role.name()))return true;return false;}
    public JsonNode call(Contracts.Role role,String server,String name,JsonNode arguments){
        JsonNode selected=null,permission=null;
        for(var s:config.path("servers"))if(s.path("name").asText().equals(server)&&allowed(s,role))selected=s;
        require(selected!=null,"TOOL_DENIED","MCP server is not permitted for this role");
        for(var t:selected.path("tools"))if(t.path("name").asText().equals(name))permission=t;
        require(permission!=null&&permission.path("sideEffect").asText().equals("READ"),"TOOL_DENIED","Only explicitly registered read tools may run");
        ToolSchema.validate(permission.path("inputSchema"),arguments);
        URI uri=URI.create(text(selected,"url"));boolean local=Set.of("127.0.0.1","localhost","::1","[::1]").contains(uri.getHost());
        require(uri.getUserInfo()==null&&uri.getFragment()==null&&uri.getRawQuery()==null&&(uri.getScheme().equals("https")||local&&uri.getScheme().equals("http")),"MCP_URL","Use HTTPS or loopback HTTP for configured MCP servers");
        var request=HttpRequest.newBuilder();String env=selected.path("tokenEnv").asText("");
        if(!env.isBlank()){String token=System.getenv(env);if(token==null)throw new Fault(503,"MCP_CREDENTIAL","Configured MCP credential is missing");request.header("Authorization","Bearer "+token);}
        try{
            String origin=uri.getScheme()+"://"+uri.getRawAuthority();
            var transport=HttpClientStreamableHttpTransport.builder(origin).endpoint(uri.getRawPath()).clientBuilder(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER))
                .requestBuilder(request).maxResponseSize(100000).connectTimeout(Duration.ofSeconds(5)).build();
            try(var client=McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).initializationTimeout(Duration.ofSeconds(10)).build()){
                client.initialize();var tools=client.listTools().tools();var tool=tools.stream().filter(t->t.name().equals(name)).findFirst().orElseThrow(()->new Fault(422,"MCP_TOOL_MISSING","Configured MCP tool is unavailable"));
                require(hash(tool.inputSchema()).equals(hash(permission.path("inputSchema"))),"MCP_SCHEMA_CHANGED","Discovered input schema differs from approved schema");
                var result=client.callTool(new McpSchema.CallToolRequest(name,MAPPER.convertValue(arguments,Map.class)));
                if(Boolean.TRUE.equals(result.isError()))throw new Fault(503,"MCP_TOOL_ERROR","MCP tool returned an error");
                var output=obj("structuredContent",result.structuredContent(),"content",result.content());
                require(write(output).length()<=30000,"MCP_OUTPUT_LIMIT","MCP result exceeds permitted size");
                return obj("toolReceiptId",id(),"source",server+"."+name,"sourceClass",selected.path("sourceClass").asText("PRIMARY_PUBLIC_RESEARCH"),"retrievedAt",java.time.Instant.now().toString(),"output",output);
            }
        }catch(Fault e){throw e;}catch(Exception e){throw new Fault(503,"MCP_UNAVAILABLE","Configured MCP server is unavailable");}
    }
}
