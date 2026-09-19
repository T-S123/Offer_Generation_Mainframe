/**
 * Independently hosted, authenticated credit-bureau API; only the integration engine holds this service
 * credential.
 */
package com.lending.engine.credit;

import com.lending.engine.api.ApiServer;
import com.lending.engine.bureau.domain.Bureau.*;
import com.lending.engine.domain.Model.Problem;
import com.lending.engine.infrastructure.Json;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * Independently hosted, authenticated credit-bureau API; only the integration engine holds this service
 * credential.
 */
public final class CreditServer implements AutoCloseable {
    private final HttpServer server;private final ExecutorService workers=Executors.newFixedThreadPool(12);
    /** Initializes credit server with the supplied configuration and dependencies. */
    public CreditServer(CreditEngine engine,int port,String token)throws java.io.IOException {
        byte[] credential=("Bearer "+token).getBytes(StandardCharsets.UTF_8);server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),64);server.setExecutor(workers);
        server.createContext("/api/v1/credit/",x->{try{
            String auth=x.getRequestHeaders().getFirst("Authorization");if(auth==null||!MessageDigest.isEqual(credential,auth.getBytes(StandardCharsets.UTF_8)))throw new Problem(401,"Credit service token required");if(x.getRequestHeaders().containsKey("Origin"))throw new Problem(403,"Browser origins disabled");
            String[] p=x.getRequestURI().getPath().substring("/api/v1/credit/".length()).split("/");String method=x.getRequestMethod();Object response;
            if(p.length==1&&p[0].equals("health")&&method.equals("GET"))response=Map.of("status","UP","mode","SYNTHETIC_LOCAL_BUREAU");
            else if(p.length==1&&p[0].equals("assessments")&&method.equals("POST"))response=engine.assess(ApiServer.body(x,CreditInput.class));
            else if(p.length==2&&p[0].equals("profiles")&&method.equals("POST"))response=engine.profile(p[1]);
            else if(p.length==2&&p[0].equals("profiles")&&method.equals("PUT"))response=engine.change(p[1],ApiServer.body(x,ProfileChange.class));
            else if(p.length==3&&p[0].equals("profiles")&&p[2].equals("history")&&method.equals("GET")){
                var q=x.getRequestURI().getQuery();int after=0;if(q!=null&&q.startsWith("after="))after=Integer.parseInt(q.substring(6));response=engine.history(p[1],after,100);
            }else throw new Problem(404,"Credit route not found");send(x,200,response);
        }catch(Problem e){send(x,e.status,Map.of("error",e.getMessage()));}catch(Exception e){System.err.println("Credit API failed: "+e.getClass().getSimpleName());send(x,503,Map.of("error","Credit service temporarily unavailable"));}finally{x.close();}});
    }
    /** Serializes the response body as JSON and sends it with the HTTP status. */
    private static void send(HttpExchange x,int code,Object body)throws java.io.IOException{byte[] bytes=Json.write(body).getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.getResponseHeaders().set("Cache-Control","no-store");x.sendResponseHeaders(code,bytes.length);x.getResponseBody().write(bytes);}
    /** Starts the independently authenticated local credit-service API listener. */
    public void start(){server.start();}
        /** Returns the bound local port so clients can connect to this listener. */
        public int port(){return server.getAddress().getPort();}
    /** Releases the resources owned by this component. */
    public void close(){server.stop(0);workers.shutdownNow();}
}
