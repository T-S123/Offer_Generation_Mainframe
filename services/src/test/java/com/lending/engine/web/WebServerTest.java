package com.lending.engine.web;

import com.lending.engine.infrastructure.*;
import com.lending.engine.application.CustomerEngine;
import com.lending.engine.api.ApiServer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises owner-API routing, local presenter confirmation/maintenance, session expiry and customer editing. */
class WebServerTest {
    @TempDir Path assets;
    WebServer web; HttpServer upstream;
    final AtomicReference<Map<String,String>> seen = new AtomicReference<>();
    HttpClient browser; String base, csrf;
    @BeforeEach void setup() throws Exception {
        Files.writeString(assets.resolve("index.html"),"<html>GUI fixture</html>");
        Files.createDirectories(assets.resolve("assets"));
        Files.writeString(assets.resolve("assets/app.js"),"console.log('fixture')");
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/api/v1/",x->{
            var fields=new HashMap<String,String>();fields.put("path",x.getRequestURI().toString());fields.put("method",x.getRequestMethod());
            fields.put("auth",String.valueOf(x.getRequestHeaders().getFirst("Authorization")));fields.put("origin",String.valueOf(x.getRequestHeaders().getFirst("Origin")));
            fields.put("key",String.valueOf(x.getRequestHeaders().getFirst("Idempotency-Key")));fields.put("body",new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));seen.set(fields);
            int status=x.getRequestURI().getPath().endsWith("/stale")?409:200;
            String json=status==409?"{\"error\":\"Stale version\"}":"{\"id\":\"customer-one\"}";
            byte[] bytes=json.getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);x.close();
        });upstream.start();
        connect(new WebServer(URI.create("http://127.0.0.1:"+upstream.getAddress().getPort()+"/api/v1/"),"upstream-only-test-secret",assets,0));
    }
    void connect(WebServer server) throws Exception {
        web=server;web.start();base="http://127.0.0.1:"+web.port();
        browser=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        var session=send("GET","session",null);csrf=Json.MAPPER.readTree(session.body()).path("csrfToken").asText();assertFalse(csrf.isBlank());
    }
    @AfterEach void close(){if(web!=null)web.close();if(upstream!=null)upstream.stop(0);}
    HttpResponse<String> send(String method,String path,String body,String... extra) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+"/web-api/"+path));
        if(body!=null){request.header("Content-Type","application/json");if(csrf!=null)request.header("X-CSRF-Token",csrf);}
        for(int i=0;i<extra.length;i+=2)request.header(extra[i],extra[i+1]);
        return browser.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void presenterRequiresConfirmationAndProtectsEveryWorkspaceDuringMaintenance() throws Exception {
        class Fake implements PresenterControl {
            boolean active; String generation=""; int calls;
            public com.fasterxml.jackson.databind.JsonNode invoke(String... args) throws Exception {
                calls++; return Json.MAPPER.readTree("{\"state\":\"RUNNING\",\"requestId\":\"00000000-0000-0000-0000-000000000001\"}");
            }
            public boolean maintenance(){return active;}
            public String epoch(){return generation;}
        }
        var control=new Fake();web.close();
        connect(new WebServer(URI.create("http://127.0.0.1:"+upstream.getAddress().getPort()+"/api/v1/"),"test",assets,0,control));
        String command="{\"action\":\"CLEAR\",\"requestId\":\"00000000-0000-0000-0000-000000000001\",\"confirmed\":true}";
        assertEquals(422,send("POST","presenter/reset",command.replace("true","false")).statusCode());
        assertEquals(422,send("POST","presenter/reset",command.replace("CLEAR","SHELL")).statusCode());
        assertEquals(422,send("POST","presenter/reset",command.replace("00000000-0000-0000-0000-000000000001","../path")).statusCode());
        assertEquals(403,send("POST","presenter/reset",command,"Origin","http://untrusted.example").statusCode());
        String valid=csrf;csrf="bad";assertEquals(403,send("POST","presenter/reset",command).statusCode());csrf=valid;
        assertEquals(0,control.calls);
        assertEquals(200,send("POST","presenter/reset",command).statusCode());
        send("POST","session/business","{}");control.active=true;
        assertEquals(503,send("GET","business/drafts",null).statusCode());
        assertEquals(503,send("POST","customers","{}").statusCode());
        assertEquals(200,send("GET","presenter/status",null).statusCode());
        assertEquals(200,send("GET","session",null).statusCode());
        assertEquals(200,send("POST","presenter/reset",command).statusCode());
        control.active=false;control.generation="next";
        var newSession=Json.MAPPER.readTree(send("GET","session",null).body());
        assertEquals("ANONYMOUS",newSession.path("mode").asText());
        assertNotEquals(csrf,newSession.path("csrfToken").asText());
        assertEquals(403,send("GET","business/drafts",null).statusCode());
        assertNull(seen.get());
    }
    @Test void browserCredentialNeverLeavesGatewayAndCustomerIdentityComesFromSession() throws Exception {
        assertEquals(403,send("GET","customer",null).statusCode());
        assertEquals(200,send("POST","session/customer","{\"customerId\":\"customer-one\"}").statusCode());
        assertEquals(200,send("PUT","customer","{\"expectedVersion\":3,\"customer\":{\"displayName\":\"Edited\"}}").statusCode());
        assertEquals("/api/v1/customers/customer-one",seen.get().get("path"));assertEquals("PUT",seen.get().get("method"));
        assertEquals("Bearer upstream-only-test-secret",seen.get().get("auth"));assertEquals("null",seen.get().get("origin"));
        assertTrue(seen.get().get("body").contains("\"expectedVersion\":3"));
        assertFalse(send("GET","session",null).body().contains("upstream-only-test-secret"));
        assertEquals(403,send("GET","business/drafts",null).statusCode());assertEquals(404,send("GET","customer/another-id",null).statusCode());
        assertEquals(200,send("POST","customer/offers/offer-one/selections","{\"requestId\":\"selection-one\"}").statusCode());
        assertEquals("/api/v1/offer-creation/customers/customer-one/offers/offer-one/selections",seen.get().get("path"));
    }
    @Test void customerPersonalizationUsesSessionIdentityAndCannotAccessAnotherWorkspace()throws Exception {
        send("POST","session/customer","{\"customerId\":\"customer-one\"}");
        assertEquals(200,send("POST","customer/personalization","{\"profileVersion\":3}").statusCode());
        assertEquals("/api/v1/ai/customer-personalization/customer-one",seen.get().get("path"));
        assertEquals(200,send("GET","customer/personalization/run-one",null).statusCode());assertEquals("/api/v1/ai/customer-personalization/customer-one/run-one",seen.get().get("path"));
        assertEquals(200,send("GET","customer/offer-review/offer-one",null).statusCode());assertEquals("/api/v1/offer-creation/customers/customer-one/offers/offer-one",seen.get().get("path"));
        assertEquals(403,send("GET","ai/customer-personalization/another-customer",null).statusCode());
        send("POST","session/business","{}");assertEquals(403,send("POST","customer/personalization","{\"profileVersion\":3}").statusCode());assertEquals(404,send("GET","ai/customer-personalization/another-customer",null).statusCode());
    }
    @Test void businessPreservesVersionConflictsIdempotencyAndPagination() throws Exception {
        assertEquals(200,send("POST","session/business","{}").statusCode());
        var reply=send("PUT","business/drafts/stale","{\"expectedVersion\":1}","Idempotency-Key","stable-request");
        assertEquals(409,reply.statusCode());assertTrue(reply.body().contains("Stale version"));assertEquals("stable-request",seen.get().get("key"));
        send("GET","ai/publications?after=last&sourceMode=DEMO",null);assertEquals("/api/v1/ai/publications?after=last&sourceMode=DEMO",seen.get().get("path"));
        for(String route:List.of("ai/workflows?network=SIMULATION&limit=20", "ai/simulation/workflows", "ai/feedback/workflows?after=last", "ai/performance/publications", "ai/performance/publications/example?sourceMode=DEMO")){
            assertEquals(200,send("GET",route,null).statusCode());assertEquals("/api/v1/"+route,seen.get().get("path"));
        }
        send("POST","session/customer","{\"customerId\":\"customer-one\"}");
        assertEquals(403,send("GET","ai/simulation/workflows",null).statusCode());
        assertEquals(403,send("GET","ai/feedback/workflows",null).statusCode());
        send("POST","session/business","{}");
        assertEquals(404,send("GET","business/not-a-resource",null).statusCode());
        assertEquals(422,send("GET","ai/workflows/../private",null).statusCode());
    }
    @Test void rejectsCrossOriginCsrfAndMalformedSessionCommandsBeforeUpstream() throws Exception {
        assertEquals(403,send("POST","session/business","{}","Origin","https://unrelated.example").statusCode());
        assertEquals(403,send("GET","session",null,"Sec-Fetch-Site","cross-site").statusCode());
        String current=csrf;csrf="wrong";assertEquals(403,send("POST","session/business","{}").statusCode());csrf=current;
        assertEquals(422,send("POST","session/customer","null").statusCode());
        assertEquals(422,send("POST","session/customer","{\"customerId\":\"../other\"}").statusCode());assertNull(seen.get());
        assertEquals(200,send("POST","session/business","{}","Origin",base).statusCode());
    }
    @Test void servesOnlyCompiledAssetsAndProtectsDemoCookie() throws Exception {
        var response=browser.send(HttpRequest.newBuilder(URI.create(base+"/")).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());assertTrue(response.body().contains("GUI fixture"));
        assertTrue(response.headers().firstValue("Content-Security-Policy").orElse("").contains("frame-ancestors 'none'"));
        var fresh=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base+"/web-api/session")).build(),HttpResponse.BodyHandlers.ofString());
        String cookie=fresh.headers().firstValue("Set-Cookie").orElse("");assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("SameSite=Strict"));
        for(String path:List.of("/runtime/api-token","/assets/missing.js","/%2e%2e/secret")){
            assertNotEquals(200,browser.send(HttpRequest.newBuilder(URI.create(base+path)).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    @Test void logoutDropsCustomerAndBusinessCapabilities() throws Exception {
        send("POST","session/customer","{\"customerId\":\"customer-one\"}");send("POST","session/logout","{}");
        assertEquals(403,send("GET","customer",null).statusCode());assertEquals(403,send("POST","business/runs","{}").statusCode());
    }
    @Test void createReadEditAndStaleVersionUseTheRealCustomerApplication() throws Exception {
        web.close();
        try(var store=new SqlStore("jdbc:h2:mem:"+UUID.randomUUID());
            var engine=new CustomerEngine(store,inputs->inputs.stream().map(input->List.<com.lending.engine.domain.Model.RuleResult>of()).toList(),Clock.systemUTC());
            var owner=new ApiServer(engine,0,"owner-test-token")){
            owner.start();connect(new WebServer(URI.create("http://127.0.0.1:"+owner.port()+"/api/v1/"),"owner-test-token",assets,0));
            String input="{\"displayName\":\"GUI Test\",\"monthlyIncomeUsd\":7000,\"existingProducts\":[],\"preferredChannels\":[]}";
            var created=send("POST","customers",input);assertEquals(201,created.statusCode(),created.body());
            var saved=Json.MAPPER.readTree(created.body());String customerId=saved.path("id").asText();assertFalse(customerId.isBlank());
            var resolved=browser.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+owner.port()+"/api/v1/customers/resolve?reference="+customerId)).header("Authorization","Bearer owner-test-token").GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,resolved.statusCode());assertEquals(customerId,Json.MAPPER.readTree(resolved.body()).path("customerId").asText());
            assertEquals("CUSTOMER",Json.MAPPER.readTree(send("GET","session",null).body()).path("mode").asText());
            var read=Json.MAPPER.readTree(send("GET","customer",null).body());assertEquals(7000,read.at("/profiles/0/data/monthlyIncomeUsd").asInt());
            String edited="{\"expectedVersion\":1,\"customer\":"+input.replace("GUI Test","Updated Name")+"}";
            var update=send("PUT","customer",edited);assertEquals(200,update.statusCode(),update.body());
            var data=Json.MAPPER.readTree(update.body());assertEquals(2,data.path("profiles").size());assertEquals("Updated Name",data.at("/profiles/1/data/displayName").asText());
            assertEquals(409,send("PUT","customer",edited).statusCode());
            send("POST","session/logout","{}");assertEquals(200,send("POST","session/customer","{\"customerId\":\""+customerId+"\"}").statusCode());
            assertEquals(2,Json.MAPPER.readTree(send("GET","customer",null).body()).path("profiles").size());
        }
    }
}

