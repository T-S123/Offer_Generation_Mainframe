package com.lending.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static com.lending.ai.Json.*;
import static com.lending.ai.Contracts.*;

/** Loopback interaction API and authenticated A2A server; only analyst requests can confirm scope or publication. */
public final class Server implements AutoCloseable {
    private final HttpServer server;private final ExecutorService pool=Executors.newFixedThreadPool(8);
    private final Role role;private final String analyst;private final Map<String,String> credentials;private final A2AService a2a;
    private final Store store;private final WorkflowService workflows;private final PublicationService publications;private final OutcomeService outcomes;
    private final PolicyIndex policies;private final Http research;
    private SourceFacts sourceFacts;
    public void sourceFacts(SourceFacts facts){sourceFacts=facts;}
    private final FeedbackService feedback;private final ConversationService conversations;
    public Server(int port,Role role,String analyst,Map<String,String> credentials,A2AService a2a,Store store,WorkflowService workflows,PublicationService publications,OutcomeService outcomes,PolicyIndex policies,Http research)throws Exception{
        this.role=role;this.analyst=analyst;this.credentials=Map.copyOf(credentials);this.a2a=a2a;this.store=store;this.workflows=workflows;this.publications=publications;this.outcomes=outcomes;this.policies=policies;this.research=research;
        feedback=workflows==null||outcomes==null?null:new FeedbackService(store,workflows,workflows.engine(),outcomes);
        conversations=feedback==null?null:new ConversationService(store,workflows,publications,feedback);
        if(workflows!=null&&research!=null)workflows.evidenceValidator((product,ids)->research.call("POST","api/v1/ai/policies/validate",obj("product",product,"evidenceIds",ids)));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),32);server.createContext("/",this::handle);server.setExecutor(pool);
    }
    public FeedbackService feedback(){return feedback;}
    public void start(){server.start();}public int port(){return server.getAddress().getPort();}
    private String principal(HttpExchange x){
        String auth=x.getRequestHeaders().getFirst("Authorization");if(auth==null)throw new Fault(401,"AUTHENTICATION","Service or analyst credential required");
        for(var entry:credentials.entrySet())if(MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8),("Bearer "+entry.getValue()).getBytes(StandardCharsets.UTF_8)))return entry.getKey();
        throw new Fault(401,"AUTHENTICATION","Invalid credential");
    }
    private void handle(HttpExchange x)throws java.io.IOException {
        try{
            if(x.getRequestHeaders().getFirst("Origin")!=null)throw new Fault(403,"ORIGIN","Browser-origin requests are disabled");
            String who=principal(x),path=x.getRequestURI().getPath(),method=x.getRequestMethod();Object result;
            String protocol=x.getRequestHeaders().getFirst("A2A-Version");
            if(path.equals("/a2a")&&protocol!=null&&!protocol.equals("1.0"))throw new Fault(422,"PROTOCOL_VERSION","Only A2A 1.0 is supported");
            x.getResponseHeaders().set("A2A-Version","1.0");
            if(path.equals("/api/v1/ai/health")&&method.equals("GET"))result=obj("status","UP","role",role,"model",AstraClient.MODEL,"protocol","A2A/1.0","modelConfigured",System.getenv("OPENAI_API_KEY")!=null);
            else if(path.equals("/internal/outcomes/events")&&method.equals("POST")&&sourceFacts!=null){require(who.equals("outcomes"),"SOURCE_AUTHORITY","Operational source credential required");result=sourceFacts.ingest(Http.body(x));}
            else if(path.equals("/.well-known/agent-card.json")&&method.equals("GET"))result=a2a.card("http://127.0.0.1:"+port()+"/a2a");
            else if(path.equals("/a2a")&&method.equals("POST")){
                require(who.equals("ORCHESTRATOR"),"AGENT_AUTHORITY","Only registered orchestrator delegations are accepted");result=a2a.rpc(Http.body(x),who);
            }else if(path.equals("/internal/usage")&&method.equals("POST")&&workflows!=null){
                require(Set.of("ORCHESTRATOR","RESEARCH","DESIGNER","COORDINATOR","ANALYZER","REFLECTION").contains(who),"AGENT_AUTHORITY","Agent credential required");
                var b=Http.body(x);fields(b,"request","callId","actualTokens");workflows.settle(Role.valueOf(who),convert(b.path("request"),Request.class),text(b,"callId"),b.path("actualTokens").asLong());result=obj("settled",true);
            }else if(path.equals("/internal/budget")&&method.equals("POST")&&workflows!=null){
                require(Set.of("ORCHESTRATOR","RESEARCH","DESIGNER","COORDINATOR","ANALYZER","REFLECTION").contains(who),"AGENT_AUTHORITY","Agent credential required");var body=Http.body(x);fields(body,"request","callId","maxTokens");
                workflows.reserve(Role.valueOf(who),convert(body.path("request"),Request.class),text(body,"callId"),body.path("maxTokens").asLong());result=obj("reserved",true);
            }else{
                require(who.equals("analyst"),"ANALYST_REQUIRED","This operation requires an analyst credential");
                if(!path.startsWith("/api/v1/ai/"))throw new Fault(404,"NOT_FOUND","Route not found");
                String[] p=path.substring(11).split("/");var q=Http.query(x.getRequestURI().getRawQuery());
                result=analystRoute(method,p,q,x);
            }
            Http.send(x,200,result);
        }catch(Fault e){Http.send(x,e.status,obj("code",e.code,"error",e.getMessage()));}
        catch(Exception e){System.err.println("AI request failed: "+e.getClass().getSimpleName());Http.send(x,503,obj("code","AI_UNAVAILABLE","error","AI operation failed; no success is implied"));}
        finally{x.close();}
    }
    private Object analystRoute(String method,String[] p,Map<String,String> q,HttpExchange x)throws Exception {
        if(p[0].equals("health")&&method.equals("GET"))return obj("status","UP","role",role,"model",AstraClient.MODEL,"protocol","A2A/1.0","modelConfigured",System.getenv("OPENAI_API_KEY")!=null);
        if(p[0].equals("policies")){
            if(policies==null){require(research!=null,"POLICY_SERVICE","Research service unavailable");return research.call(method,"api/v1/ai/"+String.join("/",p)+(x.getRequestURI().getRawQuery()==null?"":"?"+x.getRequestURI().getRawQuery()),method.equals("GET")?null:Http.body(x));}
            if(p.length==1&&method.equals("GET"))return obj("documents",policies.documents());
            if(p.length==2&&p[1].equals("validate")&&method.equals("POST")){var b=Http.body(x);fields(b,"product","evidenceIds");policies.validateEvidence(text(b,"product"),List.copyOf(WorkflowService.strings(b.path("evidenceIds"))));return obj("valid",true);}
            if(p.length==2&&p[1].equals("refresh")&&method.equals("POST"))return policies.scan();
            if(p.length==3&&p[2].equals("text")&&method.equals("GET")){var d=store.get("DOCUMENT",p[1]).data();String text=store.artifact(d.path("textArtifact").asText()).path("text").asText();int offset=Integer.parseInt(q.getOrDefault("offset","0"));require(offset>=0&&offset<=text.length(),"PAGE_OFFSET","Invalid text offset");return obj("document",d,"offset",offset,"text",text.substring(offset,Math.min(offset+8000,text.length())),"totalCharacters",text.length());}
            if(p.length==3&&p[2].equals("approve")&&method.equals("POST")){var b=Http.body(x);fields(b,"hash","product","geography","expiresOn");return policies.approve(p[1],text(b,"hash"),text(b,"product"),text(b,"geography"),text(b,"expiresOn"),analyst);}
        }
        if(workflows==null)throw new Fault(404,"NOT_FOUND","Interaction route belongs to the orchestrator service");
        var calibration=new CalibrationService(store,Integer.parseInt(System.getenv().getOrDefault("AI_FOLLOW_UP_DAYS","14")));
        if(p[0].equals("calibrations")){
            if(p.length==1&&method.equals("GET"))return calibration.readiness(q.getOrDefault("product","PERSONAL_LOAN"),q.getOrDefault("policyContext",""));
            if(p.length==1&&method.equals("POST"))return calibration.train(analyst,Http.body(x));
            if(p.length==2&&p[1].equals("activate")&&method.equals("POST"))return calibration.activate(analyst,Http.body(x));
            if(p.length==2&&p[1].equals("forecast")&&method.equals("POST")){var b=Http.body(x);fields(b,"product","policyContext","probabilities");var probabilities=new ArrayList<Double>();for(var v:b.path("probabilities")){require(v.isNumber(),"INVALID_PROBABILITY","Numeric probabilities required");probabilities.add(v.asDouble());}return calibration.forecast(text(b,"product"),text(b,"policyContext"),probabilities);}
        }
        if(p[0].equals("conversations")&&conversations!=null){
            if(p.length==1&&method.equals("POST"))return conversations.create(analyst,Http.body(x));
            if(p.length==2&&method.equals("GET"))return conversations.view(p[1],analyst);
            if(p.length==3&&p[2].equals("messages")&&method.equals("POST"))return conversations.message(p[1],analyst,Http.body(x));
        }
        if(p[0].equals("notices")&&method.equals("GET"))return store.list("NOTICE",q.get("after"),20).stream().filter(v->v.data().path("analyst").asText().equals(analyst)).toList();
        if(p.length==3&&p[0].equals("publications")&&method.equals("POST")&&feedback!=null)return switch(p[2]){
            case "forecasts"->calibration.freezeForecast(p[1],analyst,Http.body(x));
            case "investigations"->feedback.create(p[1],analyst,Http.body(x));
            case "successors"->feedback.successor(p[1],analyst,Http.body(x));
            default->throw new Fault(404,"NOT_FOUND","Publication action not found");};
        if(p[0].equals("parameters")&&method.equals("GET"))return new ParameterRegistry().all();
        if(p[0].equals("defaults")&&method.equals("GET"))return defaults();
        if(p[0].equals("workflows")){
            if(p.length==1&&method.equals("POST"))return workflows.create(Http.body(x),analyst);
            if(p.length==1&&method.equals("GET"))return workflows.list(analyst,q.get("after"),Integer.parseInt(q.getOrDefault("limit","20")));
            if(p.length==3&&p[2].equals("evaluations")&&method.equals("GET"))return workflows.evaluations(p[1],analyst,Integer.parseInt(q.getOrDefault("offset","0")),Integer.parseInt(q.getOrDefault("limit","20")));
            if(p.length==4&&p[2].equals("artifacts")&&method.equals("GET"))return workflows.artifact(p[1],analyst,p[3]);
            if(p.length==2&&method.equals("GET"))return workflows.view(workflows.get(p[1],analyst));
            if(p.length==3&&method.equals("POST"))return switch(p[2]){
                case "scope-confirmations"->workflows.confirm(p[1],analyst,Http.body(x));
                case "messages"->workflows.message(p[1],analyst,Http.body(x));
                case "resume"->workflows.resume(p[1],analyst,Http.body(x));
                case "cancel"->workflows.cancel(p[1],analyst);
                case "publication-previews"->publications.preview(p[1],analyst,Http.body(x));
                default->throw new Fault(404,"NOT_FOUND","Workflow action not found");};
        }
        if(p.length==3&&p[0].equals("publication-previews")&&p[2].equals("confirm")&&method.equals("POST"))return publications.confirm(p[1],analyst,Http.body(x));
        if(p[0].equals("publications")&&method.equals("GET")){
            if(p.length==1)return store.list("PUBLICATION",q.get("after"),20).stream().filter(v->v.data().path("analyst").asText().equals(analyst)).toList();
            var pub=store.get("PUBLICATION",p[1]).data();if(!pub.path("analyst").asText().equals(analyst))throw new Fault(404,"NOT_FOUND","Publication not found");
            if(p.length==3&&p[2].equals("performance")){return feedback.performance(p[1],analyst,q.getOrDefault("sourceMode","DEMO"));}
        }
        if(p.length==2&&p[0].equals("outcomes")&&p[1].equals("refresh")&&method.equals("POST"))return outcomes.synchronize();
        throw new Fault(404,"NOT_FOUND","AI route not found");
    }
    public static Store.Budget defaults(){return new Store.Budget(200,60,500000,new java.math.BigDecimal(System.getenv().getOrDefault("AI_DEFAULT_MAX_USD","25")),Instant.now().plusSeconds(1800).toString(),119,60,20,30,0,0);}
    public void close(){server.stop(0);pool.shutdownNow();}
}
