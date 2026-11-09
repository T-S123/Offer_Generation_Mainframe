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

/** Loopback facade serves model-free Performance, server-owned budget suggestions and role-scoped network tools alongside compatible workflow routes. */
public final class Server implements AutoCloseable {
    private final HttpServer server;private final ExecutorService pool=Executors.newFixedThreadPool(8);
    private final Role role;private final String analyst;private final Map<String,String> credentials;private final A2AService a2a;
    private final Store store;private final WorkflowService workflows;private final PublicationService publications;private final OutcomeService outcomes;
    private final PolicyIndex policies;private final Http research;
    private PersonalizationWorkflowService personalization;
    public Server personalization(PersonalizationWorkflowService value){personalization=value;if(router!=null)router.personalization(value);return this;}
    private SourceFacts sourceFacts;
    public void sourceFacts(SourceFacts facts){sourceFacts=facts;}
    private boolean agentsEnabled=true;private final PerformanceService performance;
    public Server agentsEnabled(boolean enabled){agentsEnabled=enabled;return this;}
    private final FeedbackService feedback;private final ConversationService conversations;private final WorkflowRouter router;
    public Server(int port,Role role,String analyst,Map<String,String> credentials,A2AService a2a,Store store,WorkflowService workflows,PublicationService publications,OutcomeService outcomes,PolicyIndex policies,Http research)throws Exception{
        this(port,role,analyst,credentials,a2a,store,workflows,publications,outcomes,policies,research,null);
    }
    public Server(int port,Role role,String analyst,Map<String,String> credentials,A2AService a2a,Store store,WorkflowService workflows,PublicationService publications,OutcomeService outcomes,PolicyIndex policies,Http research,InvestigationWorkflowService investigations)throws Exception{
        this.role=role;this.analyst=analyst;this.credentials=Map.copyOf(credentials);this.a2a=a2a;this.store=store;this.workflows=workflows;this.publications=publications;this.outcomes=outcomes;this.policies=policies;this.research=research;
        router=workflows==null?null:new WorkflowRouter(store,workflows,investigations);
        feedback=workflows==null||outcomes==null||investigations==null?null:new FeedbackService(store,investigations,workflows,workflows.engine(),outcomes);
        performance=outcomes==null?null:new PerformanceService(store,outcomes,workflows==null?null:workflows.engine(),Integer.parseInt(System.getenv().getOrDefault("AI_FOLLOW_UP_DAYS","14")));
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
            if(path.equals("/api/v1/ai/health")&&method.equals("GET"))result=obj("status","UP","network",a2a.network(),"role",role,"model",AstraClient.MODEL,"protocol","A2A/1.0","agentsEnabled",agentsEnabled,"modelConfigured",agentsEnabled&&System.getenv("OPENAI_API_KEY")!=null);
            else if(path.equals("/internal/outcomes/events")&&method.equals("POST")&&sourceFacts!=null){require(who.equals("outcomes"),"SOURCE_AUTHORITY","Operational source credential required");result=sourceFacts.ingest(Http.body(x));}
            else if(path.equals("/api/v1/ai/policies/search")&&method.equals("POST")&&who.equals("policyReader")){
                require(policies!=null,"POLICY_SERVICE","Policy evidence belongs to Research");var b=Http.body(x);fields(b,"query","product");policies.scan();result=obj("evidence",policies.search(text(b,"query"),text(b,"product"),8),"sourceClass","APPROVED_INTERNAL_POLICY");
            }
            else if(path.equals("/.well-known/agent-card.json")&&method.equals("GET"))result=a2a.card("http://127.0.0.1:"+port()+"/a2a");
            else if(path.equals("/a2a")&&method.equals("POST")){
                require(agentsEnabled,"AGENTS_DISABLED","Agent execution is disabled in Performance API mode");require(who.equals("ORCHESTRATOR"),"AGENT_AUTHORITY","Only registered orchestrator delegations are accepted");result=a2a.rpc(Http.body(x),who);
            }else if(path.equals("/internal/investigation-tools")&&method.equals("POST")&&feedback!=null){
                require(agentsEnabled,"AGENTS_DISABLED","Agent execution is disabled");var agent=agentPrincipal(who);require(agent.network()==Network.FEEDBACK,"NETWORK_SCOPE","Investigation credential required");var b=Http.body(x);fields(b,"request","name","arguments");result=feedback.tool(agent.role(),convert(b.path("request"),Request.class),text(b,"name"),b.path("arguments"));
            }else if(path.equals("/internal/personalization-tools")&&method.equals("POST")&&personalization!=null){
                require(agentsEnabled,"AGENTS_DISABLED","Agent execution is disabled");var agent=agentPrincipal(who);require(agent.network()==Network.PERSONALIZATION,"NETWORK_SCOPE","Customer-network credential required");var b=Http.body(x);fields(b,"request","name","arguments");result=personalization.tool(agent.role(),convert(b.path("request"),Request.class),text(b,"name"),b.path("arguments"));
            }else if(path.equals("/internal/usage")&&method.equals("POST")&&workflows!=null){
                var agent=agentPrincipal(who);
                var b=Http.body(x);fields(b,"request","callId","actualTokens");router.network(agent.network()).settle(agent.role(),convert(b.path("request"),Request.class),text(b,"callId"),b.path("actualTokens").asLong());result=obj("settled",true);
            }else if(path.equals("/internal/budget")&&method.equals("POST")&&workflows!=null){
                require(agentsEnabled,"AGENTS_DISABLED","Agent execution is disabled");var agent=agentPrincipal(who);var body=Http.body(x);fields(body,"request","callId","maxTokens");
                router.network(agent.network()).reserve(agent.role(),convert(body.path("request"),Request.class),text(body,"callId"),body.path("maxTokens").asLong());result=obj("reserved",true);
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
    private record AgentPrincipal(Network network,Role role) {}
    private static AgentPrincipal agentPrincipal(String who){
        Network network=who.startsWith("PERSONALIZATION_")?Network.PERSONALIZATION:who.startsWith("FEEDBACK_")?Network.FEEDBACK:Network.SIMULATION;
        String name=network==Network.SIMULATION?who:who.substring(network.name().length()+1);
        try{Role role=Role.valueOf(name);require(network.roles().contains(role),"AGENT_AUTHORITY","Agent role unavailable");return new AgentPrincipal(network,role);}
        catch(IllegalArgumentException e){throw new Fault(403,"AGENT_AUTHORITY","Agent credential required");}
    }
    private Object analystRoute(String method,String[] p,Map<String,String> q,HttpExchange x)throws Exception {
        if(p[0].equals("health")&&method.equals("GET"))return obj("status","UP","network",a2a.network(),"role",role,"model",AstraClient.MODEL,"protocol","A2A/1.0","agentsEnabled",agentsEnabled,"modelConfigured",agentsEnabled&&System.getenv("OPENAI_API_KEY")!=null);
        if(p[0].equals("performance")&&performance!=null){
            if(p.length==2&&p[1].equals("publications")&&method.equals("GET"))return performance.publicationPage(analyst,q.get("after"));
            if(p.length>=3&&p[1].equals("publications")){
                if(p.length==3&&method.equals("GET"))return performance.summary(p[2],analyst,q.getOrDefault("sourceMode","DEMO"));
                if(p.length==4&&p[3].equals("measurements")&&method.equals("POST"))return performance.query(p[2],analyst,Http.body(x));
                if(p.length==4&&p[3].equals("eligibility")&&method.equals("POST")){var b=Http.body(x);fields(b,"customerReference","scope");return performance.trace(p[2],analyst,text(b,"customerReference"),b.hasNonNull("scope")?b.path("scope"):obj());}
                if(p.length==5&&p[3].equals("records")&&method.equals("GET"))return performance.record(p[2],analyst,p[4]);
                if(p.length==5&&p[3].equals("evidence")&&method.equals("GET")){performance.publication(p[2],analyst);var artifact=store.artifact(p[4]);require(artifact.path("publicationId").asText().equals(p[2])||artifact.at("/comparison/observed/publicationId").asText().equals(p[2])||artifact.at("/observed/offerId").asText().equals(p[2]),"EVIDENCE_SCOPE","Evidence belongs to another publication");return artifact;}
            }
            throw new Fault(404,"NOT_FOUND","Performance route not found");
        }
        if(!agentsEnabled&&!method.equals("GET"))throw new Fault(503,"AGENTS_DISABLED","Start the AI networks to run an investigation; performance reads remain available");
        if(p[0].equals("customer-personalization")&&personalization!=null){
            require(p.length>=2&&p[1].matches("[A-Za-z0-9_.:-]{1,120}"),"CUSTOMER_SCOPE","Customer required");String customer=p[1];
            if(p.length==2&&method.equals("GET"))return personalization.latest(customer,Integer.parseInt(q.getOrDefault("profileVersion","0")));
            if(p.length==2&&method.equals("POST"))return personalization.create(Http.body(x),customer);
            if(p.length==3&&method.equals("GET"))return personalization.customerView(customer,p[2]);
            if(p.length==4&&method.equals("POST")&&p[3].equals("cancel"))return personalization.cancelCustomer(customer,p[2]);
            if(p.length==4&&method.equals("POST")&&p[3].equals("messages"))return personalization.reply(customer,p[2],Http.body(x));
            throw new Fault(404,"NOT_FOUND","Customer personalization operation not found");
        }
        if(p[0].equals("policies")){
            if(policies==null){require(research!=null,"POLICY_SERVICE","Research service unavailable");return research.call(method,"api/v1/ai/"+String.join("/",p)+(x.getRequestURI().getRawQuery()==null?"":"?"+x.getRequestURI().getRawQuery()),method.equals("GET")?null:Http.body(x));}
            if(p.length==1&&method.equals("GET"))return obj("documents",policies.documents());
            if(p.length==2&&p[1].equals("validate")&&method.equals("POST")){var b=Http.body(x);fields(b,"product","evidenceIds");policies.validateEvidence(text(b,"product"),List.copyOf(WorkflowService.strings(b.path("evidenceIds"))));return obj("valid",true);}
            if(p.length==2&&p[1].equals("refresh")&&method.equals("POST"))return policies.scan();
            if(p.length==3&&p[2].equals("text")&&method.equals("GET")){var d=store.get("DOCUMENT",p[1]).data();String text=store.artifact(d.path("textArtifact").asText()).path("text").asText();int offset=Integer.parseInt(q.getOrDefault("offset","0"));require(offset>=0&&offset<=text.length(),"PAGE_OFFSET","Invalid text offset");return obj("document",d,"offset",offset,"text",text.substring(offset,Math.min(offset+8000,text.length())),"totalCharacters",text.length());}
            if(p.length==3&&p[2].equals("approve")&&method.equals("POST")){var b=Http.body(x);fields(b,"hash","product","geography","expiresOn");return policies.approve(p[1],text(b,"hash"),text(b,"product"),text(b,"geography"),text(b,"expiresOn"),analyst);}
        }
        if(workflows==null)throw new Fault(404,"NOT_FOUND","Interaction route belongs to the orchestrator service");
        if(p.length==2&&p[0].equals("feedback")&&p[1].equals("investigations")&&method.equals("POST")&&feedback!=null){var b=(com.fasterxml.jackson.databind.node.ObjectNode)Http.body(x);String publication=b.path("publicationId").asText("");b.remove("publicationId");return feedback.create(publication,analyst,b);}
        Network selectedNetwork=null;
        if(p[0].equals("simulation")||p[0].equals("feedback")){
            selectedNetwork=Network.parse(p[0].toUpperCase(Locale.ROOT));
            require(p.length>=2&&p[1].equals("workflows"),"NETWORK_OPERATION","Use this network's workflow routes");p=Arrays.copyOfRange(p,1,p.length);
        }
        if(q.containsKey("network")){var filter=Network.parse(q.get("network"));require(selectedNetwork==null||selectedNetwork==filter,"NETWORK_SCOPE","Network filters disagree");selectedNetwork=filter;}
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
        if(p[0].equals("defaults")&&method.equals("GET")){
            if(!q.containsKey("maxEvaluations"))return defaults();
            String total=q.get("maxEvaluations");require(total.matches("[0-9]{1,4}"),"BUDGET_INVALID","Total simulation limit must be an integer from 4 to 1000");
            return defaults(Integer.parseInt(total));
        }
        if(p[0].equals("workflows")){
            if(p.length==1&&method.equals("POST"))return router.network(selectedNetwork==null?Network.SIMULATION:selectedNetwork).create(Http.body(x),analyst);
            if(p.length==1&&method.equals("GET"))return router.list(analyst,q.get("after"),Integer.parseInt(q.getOrDefault("limit","20")),selectedNetwork);
            var owner=selectedNetwork==null?router.owner(p[1]):router.network(selectedNetwork);owner.get(p[1],analyst);
            if(p.length==3&&p[2].equals("evaluations")&&method.equals("GET"))return owner.evaluations(p[1],analyst,Integer.parseInt(q.getOrDefault("offset","0")),Integer.parseInt(q.getOrDefault("limit","20")));
            if(p.length==4&&p[2].equals("artifacts")&&method.equals("GET"))return owner.artifact(p[1],analyst,p[3]);
            if(p.length==2&&method.equals("GET"))return owner.view(owner.get(p[1],analyst));
            if(p.length==3&&method.equals("POST"))return switch(p[2]){
                case "scope-confirmations"->owner.confirm(p[1],analyst,Http.body(x));
                case "messages"->owner.message(p[1],analyst,Http.body(x));
                case "resume"->owner.resume(p[1],analyst,Http.body(x));
                case "cancel"->owner.cancel(p[1],analyst);
                case "publication-previews"->publications.preview(p[1],analyst,Http.body(x));
                default->throw new Fault(404,"NOT_FOUND","Workflow action not found");};
        }
        if(p.length==3&&p[0].equals("publication-previews")&&p[2].equals("confirm")&&method.equals("POST"))return publications.confirm(p[1],analyst,Http.body(x));
        if(p[0].equals("publications")&&method.equals("GET")){
            if(p.length==1)return performance!=null?performance.publicationPage(analyst,q.get("after")):store.list("PUBLICATION",q.get("after"),20).stream().filter(v->v.data().path("analyst").asText().equals(analyst)).toList();
            var pub=store.get("PUBLICATION",p[1]).data();if(!pub.path("analyst").asText().equals(analyst))throw new Fault(404,"NOT_FOUND","Publication not found");
            if(p.length==3&&p[2].equals("performance")){return feedback.performance(p[1],analyst,q.getOrDefault("sourceMode","DEMO"));}
        }
        if(p.length==2&&p[0].equals("outcomes")&&p[1].equals("refresh")&&method.equals("POST"))return outcomes.synchronize();
        throw new Fault(404,"NOT_FOUND","AI route not found");
    }
    public static Store.Budget defaults(){return new Store.Budget(200,60,500000,new java.math.BigDecimal(System.getenv().getOrDefault("AI_DEFAULT_MAX_USD","25")),Instant.now().plusSeconds(1800).toString(),119,60,20,30,0,0);}
    /** Recommends a valid phase split for a new run; callers retain their other budget choices and existing runs never rebalance. */
    public static Store.Budget defaults(int total){
        require(total>=4&&total<=1000,"BUDGET_INVALID","Total simulation limit must be an integer from 4 to 1000");
        var base=defaults();int validation=Math.max(2,total/10);
        int exploration=Math.max(1,Math.min(total-1-validation,total*3/5));
        return new Store.Budget(total,base.maxModelCalls(),base.maxTokens(),base.maxCostUsd(),base.deadline(),
            exploration,total-1-validation-exploration,validation,base.minimumEligible(),base.eligibilityFloor(),base.acceptanceFloor());
    }
    public void close(){server.stop(0);pool.shutdownNow();}
}
