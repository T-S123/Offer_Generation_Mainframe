/**
 * Local token API separates isolated Business commands from Customer Steps 1-8 and reserves callback
 * capacity for current eligibility checks.
 */
package com.lending.engine.api;

import com.lending.engine.application.CustomerEngine;
import com.lending.engine.domain.Model.*;
import com.lending.engine.infrastructure.Json;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * Local token API separates isolated Business commands from Customer Steps 1-8 and reserves callback
 * capacity for current eligibility checks.
 */
public final class ApiServer implements AutoCloseable {
    private final HttpServer server;
    private final CustomerEngine engine;
    private final com.lending.engine.marketing.api.MarketingApi marketing;
    private com.lending.engine.bureau.api.BureauApi bureau;
    private com.lending.engine.response.api.ResponseApi responses;
    private com.lending.engine.offers.OfferApi offers;
    private com.lending.engine.storage.StorageViews storage;
    private com.lending.engine.execution.ExecutionEngine execution;
    private com.lending.engine.automation.PipelineEngine pipeline;
    private com.lending.engine.simulation.api.SimulationApi business;
    /** Attaches the isolated Business simulation API to the engine router. */
    public void business(com.lending.engine.simulation.api.SimulationApi business){this.business=business;}
    private final byte[] token;
    private final ExecutorService workers=Executors.newFixedThreadPool(6);
    private final Semaphore clientRequests=new Semaphore(4);
    /** Carries import request data for API server operations. */
    public record ImportRequest(List<ImportDecision> decisions) {}
    /** Carries customer response data for API server operations. */
    public record CustomerResponse(String id, List<Profile> profiles, List<Decision> decisions,
                                   Map<Product,Decision> currentAssessments,Map<String,Object> offerView,Contact contact,Pipeline pipeline,Object executionView) {}
    /** Initializes API server with the supplied configuration and dependencies. */
    public ApiServer(CustomerEngine engine,int port,String token) throws java.io.IOException {
        this(engine,null,port,token);
    }
    /** Initializes API server with the supplied configuration and dependencies. */
    public ApiServer(CustomerEngine engine,com.lending.engine.marketing.application.MarketingEngine marketing,int port,String token) throws java.io.IOException {
        this.engine=engine; this.token=("Bearer "+token).getBytes(StandardCharsets.UTF_8);
        this.marketing=marketing==null?null:new com.lending.engine.marketing.api.MarketingApi(marketing);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),32);
        server.createContext("/",this::handle);server.setExecutor(workers);
    }
    /** Starts the local engine API listener. */
    public void start() { server.start(); }
    /** Attaches the bureau workflow API to the engine router. */
    public void bureau(com.lending.engine.bureau.api.BureauApi bureau){this.bureau=bureau;}
    /** Attaches decision-response operations to the engine router. */
    public void responses(com.lending.engine.response.api.ResponseApi responses){this.responses=responses;}
    /** Attaches offer-source and marketing-service proxy operations to the engine router. */
    public void offers(com.lending.engine.offers.OfferApi offers){this.offers=offers;}
    /** Attaches customer and enterprise offer-copy views to the engine router. */
    public void storage(com.lending.engine.storage.StorageViews storage){this.storage=storage;}
    /** Attaches campaign execution operations to the engine router. */
    public void execution(com.lending.engine.execution.ExecutionEngine execution,com.lending.engine.automation.PipelineEngine pipeline){this.execution=execution;this.pipeline=pipeline;}
    /** Returns the bound local port so clients can connect to this listener. */
    public int port() { return server.getAddress().getPort(); }
    /** Processes the incoming request and maps its outcome to the appropriate response. */
    private void handle(HttpExchange x) throws java.io.IOException {
        boolean admitted=false;
        try {
            String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
            if(path.equals("/api/v1/health") && method.equals("GET")) { send(x,200,Map.of("status","UP","runtime","JAVA_GNUCOBOL","customerTextProcessing","LOCAL_ONLY"));return; }
            String auth=x.getRequestHeaders().getFirst("Authorization");
            if(auth==null || (!MessageDigest.isEqual(token,auth.getBytes(StandardCharsets.UTF_8)) && (offers==null||!offers.authorized(path,auth)))) throw new Problem(401,"A local API token is required");
            if(x.getRequestHeaders().getFirst("Origin")!=null) throw new Problem(403,"Browser-origin requests are not enabled in this terminal profile");
            if(offers==null||!offers.authorized(path,auth)){if(!clientRequests.tryAcquire())throw new Problem(503,"Engine busy; retry shortly");admitted=true;}
            if(!path.startsWith("/api/v1/")) throw new Problem(404,"Route not found");
            String[] p=path.substring(8).split("/"); Map<String,String> q=query(x.getRequestURI().getRawQuery());
            int offset=number(q,"offset",0),limit=number(q,"limit",20);
            Object result; int status=200;
            if(p[0].equals("business")&&business!=null)result=business.route(x,p,q);
            else if(p.length==5&&p[0].equals("customers")&&p[2].equals("campaign-packages")&&p[4].equals("files")&&execution!=null&&method.equals("GET")){var pack=execution.current(p[3]);if(!pack.customerId().equals(p[1]))throw new Problem(404,"Customer package not found");result=execution.download(p[3],q.getOrDefault("format","csv"));}
            else if(Set.of("offer-source","offer-creation").contains(p[0])&&offers!=null)result=offers.route(x,p,q);
            else if(p[0].equals("campaign-execution")&&execution!=null&&method.equals("GET")){result=execution.route(p,q);if(p.length==2&&p[1].equals("health")){var h=execution.health();h.put("pipeline",pipeline==null?"DISABLED":pipeline.health());result=h;}}
            else if(p.length==3&&p[0].equals("customers")&&p[2].equals("campaign-packages")&&execution!=null&&method.equals("GET"))result=execution.customer(p[1]);
            else if(Set.of("offer-copies","warehouse").contains(p[0])&&storage!=null&&method.equals("GET"))result=storage.route(p,q);
            else if(p.length==3&&p[0].equals("customers")&&p[2].equals("offers")&&storage!=null&&method.equals("GET")){engine.get(p[1]);result=storage.customer(p[1],q.get("after"),limit);}
            else if(p.length==4&&p[0].equals("customers")&&p[2].equals("offers")&&storage!=null&&method.equals("GET")){engine.get(p[1]);result=storage.customerOffer(p[1],p[3]);}
            else if(p[0].equals("decision-responses")&&responses!=null){result=responses.route(x,p,q,limit);if(method.equals("POST"))status=202;}
            else if(p.length==3&&p[0].equals("customers")&&p[2].equals("eligible-offers")&&method.equals("GET")&&responses!=null){engine.get(p[1]);result=responses.eligible(p[1],q,limit);}
            else if(p[0].equals("marketing") && marketing!=null) result=marketing.route(x,p,q,offset,limit);
            else if(p[0].equals("bureau") && bureau!=null){result=bureau.route(x,p,q,limit);if(method.equals("POST")&&p.length==2&&Set.of("requests","batches").contains(p[1]))status=202;}
            else if(p.length==1 && p[0].equals("customers") && method.equals("POST")) {result=engine.create(body(x,CustomerInput.class));status=201;}
            else if(p.length==1 && p[0].equals("customers") && method.equals("GET")) result=engine.list(q.get("batchId"),offset,limit);
            else if(p.length==2 && p[0].equals("customers") && method.equals("GET")) result=engine.get(p[1]);
            else if(p.length==2 && p[0].equals("customers") && method.equals("PUT")) result=engine.update(p[1],body(x,Update.class));
            else if(path.equals("/api/v1/underwriting/import") && method.equals("POST")) result=engine.importDecisions(body(x,ImportRequest.class).decisions());
            else if(p.length==1 && p[0].equals("simulations") && method.equals("POST")) {if(business!=null)throw new Problem(409,"Generate isolated populations through Business > Simulation Engine or /business/populations");result=engine.simulate(body(x,SimulationRequest.class));status=202;}
            else if(p.length==1 && p[0].equals("simulations") && method.equals("GET")) result=engine.jobs();
            else if(p.length==2 && p[0].equals("simulations") && method.equals("GET")) result=engine.job(p[1]);
            else if(p.length==1 && p[0].equals("populations") && method.equals("POST")) {result=engine.createPopulation(body(x,PopulationRequest.class));status=201;}
            else if(p.length==1 && p[0].equals("populations") && method.equals("GET")) result=engine.populations();
            else if(p.length==2 && p[0].equals("populations") && method.equals("GET")) result=engine.population(p[1]);
            else if(p.length==3 && p[0].equals("populations") && p[2].equals("members") && method.equals("GET")) result=engine.members(p[1],offset,limit);
            else throw new Problem(404,"Route or method not found");
            if(result instanceof Customer customer) {
                Map<Product,Decision> current=new EnumMap<>(Product.class);
                for(Product product:Product.values()) { Decision decision=customer.latest(product);if(decision!=null)current.put(product,decision); }
                Map<String,Object> view=Map.of("status","NOT_CONFIGURED","items",List.of());if(storage!=null)try{view=storage.customer(customer.id(),null,3);}catch(Exception unavailable){view=Map.of("status","UNAVAILABLE","items",List.of());}
                Object campaigns=Map.of("status","NOT_CONFIGURED","items",List.of());if(execution!=null)try{campaigns=execution.customer(customer.id());}catch(Exception unavailable){campaigns=Map.of("status","UNAVAILABLE","items",List.of());}
                result=new CustomerResponse(customer.id(),customer.profiles(),customer.decisions(),current,view,engine.contact(customer.id()),engine.pipeline(customer.id()),campaigns);
            }
            send(x,status,result);
        } catch(Problem p) { send(x,p.status,Map.of("error",p.getMessage())); }
        catch(Exception e) { System.err.println("API request failed: "+e.getClass().getSimpleName());send(x,503,Map.of("error","Local engine unavailable; no success is implied. Check runtime and retry deliberately.")); }
        finally { if(admitted)clientRequests.release();x.close(); }
    }
    /** Reads and decodes the HTTP request body using the expected payload type. */
    public static <T> T body(HttpExchange x,Class<T> type) throws java.io.IOException {
        String content=x.getRequestHeaders().getFirst("Content-Type");
        if(content==null || !content.toLowerCase(Locale.ROOT).startsWith("application/json")) throw new Problem(415,"Content-Type must be application/json");
        byte[] bytes=x.getRequestBody().readNBytes(1024*1024+1);
        if(bytes.length>1024*1024) throw new Problem(413,"JSON body exceeds 1 MiB");
        try { T value=Json.MAPPER.readValue(bytes,type); if(value==null)throw new IllegalArgumentException();return value; }
        catch(Exception e) { throw new Problem(422,"Invalid JSON, field name, or field type; see the API contract"); }
    }
    /** Serializes the response body as JSON and sends it with the HTTP status. */
    private static void send(HttpExchange x,int status,Object value) throws java.io.IOException {
        byte[] data=Json.write(value).getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control","no-store");x.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        x.sendResponseHeaders(status,data.length);x.getResponseBody().write(data);
    }
    /** Parses a numeric API parameter and rejects invalid integer text. */
    private static int number(Map<String,String> q,String key,int fallback) {
        try{return q.containsKey(key)?Integer.parseInt(q.get(key)):fallback;}
        catch(NumberFormatException e){throw new Problem(422,"Invalid "+key);}
    }
    /** Decodes URL query parameters into a name-to-value map. */
    private static Map<String,String> query(String raw) {
        Map<String,String> result=new HashMap<>();if(raw==null)return result;
        for(String part:raw.split("&")){String[] pair=part.split("=",2);result.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),pair.length==2?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):"");}return result;
    }
    /** Releases the resources owned by this component. */
    public void close(){server.stop(0);workers.shutdownNow();}
}
