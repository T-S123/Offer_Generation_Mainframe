/**
 * Authenticated marketing APIs include stateless Business analysis isolated from active model training,
 * storage and customer selection.
 */
package com.lending.offers.api;
import com.lending.offers.Json;
import com.lending.offers.application.OfferEngine;
import com.lending.offers.infrastructure.QualificationConsumer;
import static com.lending.offers.domain.Model.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * Authenticated marketing APIs include stateless Business analysis isolated from active model training,
 * storage and customer selection.
 */
public final class OfferServer implements AutoCloseable {
    private final HttpServer server;private final OfferEngine engine;private final QualificationConsumer kafka;private final byte[] token;private final ExecutorService pool=Executors.newFixedThreadPool(6);
    private final Semaphore analysisSlot=new Semaphore(1);
    /** Initializes offer server with the supplied configuration and dependencies. */
    public OfferServer(OfferEngine engine,QualificationConsumer kafka,int port,String token)throws Exception {this.engine=engine;this.kafka=kafka;this.token=("Bearer "+token).getBytes(StandardCharsets.UTF_8);server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),32);server.createContext("/",this::handle);server.setExecutor(pool);}
    /** Starts the independent marketing HTTP API listener. */
    public void start(){server.start();}
        /** Returns the bound local port so clients can connect to this listener. */
        public int port(){return server.getAddress().getPort();}
    private com.lending.offers.application.StorageEngine storage;private com.lending.offers.infrastructure.StoragePublisher publisher;
    /** Routes marketing offer repository operations to storage queries and current eligibility checks. */
    public void storage(com.lending.offers.application.StorageEngine storage,com.lending.offers.infrastructure.StoragePublisher publisher){this.storage=storage;this.publisher=publisher;}
    /** Processes the incoming request and maps its outcome to the appropriate response. */
    private void handle(HttpExchange x)throws java.io.IOException {try{String auth=x.getRequestHeaders().getFirst("Authorization");if(auth==null||!MessageDigest.isEqual(token,auth.getBytes(StandardCharsets.UTF_8)))throw new Json.Fault(401,"Local marketing API credential required");if(x.getRequestHeaders().getFirst("Origin")!=null)throw new Json.Fault(403,"Browser-origin requests disabled");String path=x.getRequestURI().getPath();if(!path.startsWith("/api/v1/"))throw new Json.Fault(404,"Route not found");String[] p=path.substring(8).split("/");var q=query(x.getRequestURI().getRawQuery());String method=x.getRequestMethod();Object value;
        if(p.length==1&&p[0].equals("business-analysis")&&method.equals("POST")){if(!analysisSlot.tryAcquire())throw new Json.Fault(429,"An analysis is already running");try{send(x,200,com.lending.offers.domain.BusinessAnalysis.analyze(body(x,com.lending.offers.domain.BusinessAnalysis.Input.class,16*1024*1024)));}finally{analysisSlot.release();}return;}
        if(p[0].equals("storage")&&storage!=null&&method.equals("GET")){
            if(p.length==2&&p[1].equals("candidates")){send(x,200,storage.candidates(q.get("customerId"),q.get("campaignId")));return;}
            if(p.length==2)value=switch(p[1]){case "offers"->storage.journal.list(q.get("after"),Integer.parseInt(q.getOrDefault("limit","20")));case "events"->storage.journal.events(Long.parseLong(q.getOrDefault("after","0")),Integer.parseInt(q.getOrDefault("limit","100")));case "health"->{var h=storage.health();h.put("publisher",publisher==null?"DISABLED":publisher.status());yield h;}default->throw new Json.Fault(404,"Storage route not found");};
            else if(p.length==3&&p[1].equals("current"))value=storage.current(p[2]);
            else if(p.length==3&&p[1].equals("offers"))value=storage.journal.latest(p[2]);
            else if(p.length==4&&p[1].equals("offers")&&p[3].equals("history"))value=storage.journal.history(p[2],Long.parseLong(q.getOrDefault("after","0")));
            else throw new Json.Fault(404,"Storage route not found");
        }
        else if(p.length==1&&method.equals("GET"))value=switch(p[0]){case "health"->{var h=engine.health();h.put("kafka",kafka==null?"DISABLED":kafka.status());yield h;}case "metrics"->engine.store.metrics();case "offers"->engine.store.list(null,q.get("after"),Integer.parseInt(q.getOrDefault("limit","20")));case "training"->engine.store.jobs();case "models"->engine.store.models();default->throw new Json.Fault(404,"Route not found");};
        else if(p.length==1&&p[0].equals("training")&&method.equals("POST"))value=Map.of("id",engine.train(body(x,Training.class)),"status","QUEUED");
        else if(p.length==3&&p[0].equals("customers")&&p[2].equals("offers")&&method.equals("GET"))value=engine.customer(p[1],q.get("after"),Integer.parseInt(q.getOrDefault("limit","20")));
        else if(p.length==5&&p[0].equals("customers")&&p[2].equals("offers")&&p[4].equals("selections")&&method.equals("POST"))value=engine.selectPresented(p[1],p[3],body(x,PresentedSelection.class));
        else if(p.length==2&&p[0].equals("offers")&&method.equals("GET"))value=engine.current(p[1]);
        else if(p.length==3&&p[0].equals("offers")&&p[2].equals("history")&&method.equals("GET"))value=engine.store.history(p[1],Long.parseLong(q.getOrDefault("after","0")));
        else if(p.length==3&&p[0].equals("offers")&&p[2].equals("selections")&&method.equals("POST"))value=engine.select(p[1],body(x,Selection.class));
        else throw new Json.Fault(404,"Route or method not found");send(x,200,value);
    }catch(Json.Fault e){send(x,e.status,Map.of("error",e.getMessage()));}catch(NumberFormatException e){send(x,422,Map.of("error","Invalid numeric parameter"));}catch(Exception e){System.err.println("Offer API failed: "+e.getClass().getSimpleName());send(x,503,Map.of("error","Marketing service unavailable; no success is implied"));}finally{x.close();}}
    /** Reads and decodes the HTTP request body using the expected payload type. */
    private static <T>T body(HttpExchange x,Class<T> type)throws Exception{return body(x,type,1048576);}
    /** Reads and decodes the HTTP request body using the expected payload type. */
    private static <T>T body(HttpExchange x,Class<T> type,int max)throws Exception {if(!Optional.ofNullable(x.getRequestHeaders().getFirst("Content-Type")).orElse("").startsWith("application/json"))throw new Json.Fault(415,"JSON required");byte[] bytes=x.getRequestBody().readNBytes(max+1);if(bytes.length>max)throw new Json.Fault(413,"Body too large");try{T value=Json.MAPPER.readValue(bytes,type);if(value==null)throw new IllegalArgumentException();return value;}catch(Exception e){throw new Json.Fault(422,"Invalid request fields");}}
    /** Decodes URL query parameters into a name-to-value map. */
    private static Map<String,String> query(String raw){var q=new HashMap<String,String>();if(raw!=null)for(String item:raw.split("&")){String[] p=item.split("=",2);q.put(URLDecoder.decode(p[0],StandardCharsets.UTF_8),p.length==2?URLDecoder.decode(p[1],StandardCharsets.UTF_8):"");}return q;}
    /** Serializes the response body as JSON and sends it with the HTTP status. */
    private static void send(HttpExchange x,int status,Object value)throws java.io.IOException{byte[] bytes=Json.write(value).getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.getResponseHeaders().set("Cache-Control","no-store");x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);}
    /** Releases the resources owned by this component. */
    public void close(){server.stop(0);pool.shutdownNow();}
}
