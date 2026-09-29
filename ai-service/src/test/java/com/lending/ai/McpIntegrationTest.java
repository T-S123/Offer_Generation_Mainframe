package com.lending.ai;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.lending.ai.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the official MCP Streamable HTTP client against a deterministic local protocol server. */
@Timeout(40)
class McpIntegrationTest {
    @TempDir Path temp;
    @Test void configuredReadToolWorksAndSchemaChangesBlockInvocation()throws Exception{
        var calls=new AtomicInteger();var changed=new java.util.concurrent.atomic.AtomicBoolean();
        var schema=obj("type","object","properties",obj("query",obj("type","string")),"required",List.of("query"),"additionalProperties",false);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),10);
        server.createContext("/mcp",x->{try{
            if(!x.getRequestMethod().equals("POST")){x.sendResponseHeaders(405,-1);return;}
            var input=read(new String(x.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            if(!input.has("id")){x.sendResponseHeaders(202,-1);return;}
            var result=switch(input.path("method").asText()){
                case "initialize"->obj("protocolVersion",input.at("/params/protocolVersion"),"capabilities",obj("tools",obj()),"serverInfo",obj("name","test","version","1"));
                case "tools/list"->obj("tools",List.of(obj("name","search","description","Test research","inputSchema",changed.get()?obj("type","object","properties",obj(),"required",List.of(),"additionalProperties",false):schema)));
                case "tools/call"->{calls.incrementAndGet();yield obj("content",List.of(obj("type","text","text","Reviewed research excerpt")),"isError",false);}
                default->obj();
            };Http.send(x,200,obj("jsonrpc","2.0","id",input.path("id"),"result",result));
        }finally{x.close();}});
        server.start();
        try{
            Path config=temp.resolve("mcp.json");Files.writeString(config,write(obj("servers",List.of(obj("name","research","url","http://127.0.0.1:"+server.getAddress().getPort()+"/mcp","roles",List.of("RESEARCH"),"tools",List.of(obj("name","search","sideEffect","READ","inputSchema",schema)))))));
            var tools=new McpTools(config);var reply=tools.call(Contracts.Role.RESEARCH,"research","search",obj("query","credit score"));
            assertEquals("research.search",reply.path("source").asText());assertEquals(1,calls.get());
            assertThrows(Fault.class,()->tools.call(Contracts.Role.ANALYZER,"research","search",obj("query","credit score")));
            changed.set(true);assertEquals("MCP_SCHEMA_CHANGED",assertThrows(Fault.class,()->tools.call(Contracts.Role.RESEARCH,"research","search",obj("query","credit score"))).code);
            assertEquals(1,calls.get());
        }finally{server.stop(0);}
    }
}
