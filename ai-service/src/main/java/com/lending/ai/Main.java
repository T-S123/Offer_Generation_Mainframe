package com.lending.ai;

import java.nio.file.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import static com.lending.ai.Json.*;
import static com.lending.ai.Contracts.*;

/** Launches one independently hosted Java agent, initializes scoped local credentials and checks the six services. */
public final class Main {
    private Main(){}
    public static void main(String[] args)throws Exception {
        Role role=Role.valueOf(System.getenv().getOrDefault("AI_ROLE","ORCHESTRATOR"));
        Path root=Path.of(System.getProperty("engine.home",".")).toAbsolutePath().normalize(),runtime=root.resolve("runtime/ai");
        if(args.length==1&&args[0].equals("--init")){initialize(runtime);return;}
        Path credentialFile=Path.of(System.getenv().getOrDefault("AI_CREDENTIAL_FILE",runtime.resolve(role.name().toLowerCase(Locale.ROOT)+"-credentials.json").toString()));
        var auth=read(Files.readString(credentialFile));var credentials=new LinkedHashMap<String,String>();auth.fields().forEachRemaining(e->credentials.put(e.getKey(),e.getValue().asText()));
        require(credentials.containsKey("ORCHESTRATOR")&&credentials.containsKey(role.name())&&credentials.values().stream().allMatch(v->v.length()>=32)&&new HashSet<>(credentials.values()).size()==credentials.size(),"CREDENTIALS","Distinct strong service and analyst credentials required");
        int checkBase=Integer.parseInt(System.getenv().getOrDefault("AI_BASE_PORT","8100"));
        if(args.length==1&&args[0].equals("--check")){for(var r:Role.values()){var health=new Http("http://127.0.0.1:"+(checkBase+r.ordinal()),credentials.get("ORCHESTRATOR")).call("GET","api/v1/ai/health",null);require(health.path("role").asText().equals(r.name()),"ROLE_MISMATCH","Health endpoint belongs to the wrong role");}System.out.println("All six AI services are healthy.");return;}
        require(args.length==0,"ARGS","Supported commands: --init, --check, or no arguments");
        String analyst=System.getenv().getOrDefault("AI_ANALYST_ID","local-analyst");
        String url=System.getenv().getOrDefault("AI_DATABASE_URL",System.getenv().getOrDefault("DATABASE_URL",""));
        require(!url.isBlank(),"DATABASE_CONFIG","AI_DATABASE_URL or DATABASE_URL is required");
        String user=System.getenv().getOrDefault("AI_DB_USER",""),password=System.getenv().getOrDefault("AI_DB_PASSWORD","");
        if(!url.startsWith("jdbc:")){
            URI uri=URI.create(url);if(uri.getUserInfo()!=null){String[] parts=uri.getUserInfo().split(":",2);user=parts[0];password=parts.length==2?parts[1]:"";}
            url="jdbc:postgresql://"+uri.getHost()+":"+(uri.getPort()<0?5432:uri.getPort())+uri.getPath()+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery());
        }
        require(url.startsWith("jdbc:postgresql:"),"DATABASE_CONFIG","Production AI services require PostgreSQL");
        var store=new Store(url,user,password,"ai_"+role.name().toLowerCase(Locale.ROOT));
        int basePort=Integer.parseInt(System.getenv().getOrDefault("AI_BASE_PORT","8100")),port=basePort+role.ordinal();
        var peers=new EnumMap<Role,A2AClient>(Role.class);for(var r:Role.values())peers.put(r,new A2AClient("http://127.0.0.1:"+(basePort+r.ordinal()),credentials.get("ORCHESTRATOR")));
        EngineGateway engine=null;WorkflowService workflow=null;PublicationService publication=null;OutcomeService outcomes=null;
        if(role==Role.ORCHESTRATOR){
            engine=new EngineGateway(System.getenv().getOrDefault("AI_ENGINE_URL","http://127.0.0.1:8090/api/v1/"),Files.readString(root.resolve("runtime/api-token")).trim());
            workflow=new WorkflowService(store,engine,peers);publication=new PublicationService(store,workflow,engine);
            outcomes=new OutcomeService(store,new Http(System.getenv().getOrDefault("AI_MARKETING_URL","http://127.0.0.1:8092/api/v1/"),Files.readString(root.resolve("runtime/marketing-api-token")).trim()),Integer.parseInt(System.getenv().getOrDefault("AI_FOLLOW_UP_DAYS","14")));
        }
        var policies=role==Role.RESEARCH?new PolicyIndex(Path.of(System.getenv().getOrDefault("AI_POLICY_DIR",root.resolve("policy-documents").toString())),store,Boolean.parseBoolean(System.getenv().getOrDefault("AI_ENABLE_OCR","false"))):null;
        var central=new Http("http://127.0.0.1:"+basePort,credentials.get(role.name()));WorkflowService wf=workflow;
        var tools=new AgentToolbox(policies,new McpTools(Path.of(System.getenv().getOrDefault("AI_MCP_CONFIG",root.resolve("ai-service/config/mcp-servers.json").toString()))),(request,key,tokens)->{
            if(wf!=null)wf.reserve(role,request,key,tokens);else central.call("POST","internal/budget",obj("request",request,"callId",key,"maxTokens",tokens));
        });
        tools.settlement((request,key,tokens)->{if(wf!=null)wf.settle(role,request,key,tokens);else central.call("POST","internal/usage",obj("request",request,"callId",key,"actualTokens",tokens));});
        var model=new AstraClient(System.getenv("OPENAI_API_KEY"));var a2a=new A2AService(role,store,new AgentRunner(role,model,tools).audit(store));
        var server=new Server(port,role,analyst,credentials,a2a,store,workflow,publication,outcomes,policies,new Http("http://127.0.0.1:"+(basePort+Role.RESEARCH.ordinal()),credentials.getOrDefault("policyAnalyst",credentials.get("analyst"))));
        if(role==Role.ORCHESTRATOR&&System.getenv("AI_OUTCOME_SOURCE_ID")!=null)server.sourceFacts(new SourceFacts(store,System.getenv("AI_OUTCOME_SOURCE_ID"),System.getenv().getOrDefault("AI_OUTCOME_SOURCE_MODE","OBSERVED_REAL"),Integer.parseInt(System.getenv().getOrDefault("AI_FOLLOW_UP_DAYS","14"))));
        var maintenance=Executors.newSingleThreadScheduledExecutor();OutcomeService outcome=outcomes;
        if(policies!=null)maintenance.scheduleWithFixedDelay(()->{try{policies.scan();}catch(Exception e){System.err.println("Policy refresh failed: "+e.getClass().getSimpleName());}},0,60,TimeUnit.SECONDS);
        if(outcomes!=null)maintenance.scheduleWithFixedDelay(()->{try{outcome.synchronize();server.feedback().monitor();}catch(Exception e){System.err.println("Outcome refresh pending: "+e.getClass().getSimpleName());}},10,3600,TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(()->{server.close();if(wf!=null)wf.close();maintenance.shutdownNow();a2a.close();model.close();store.close();}));
        server.start();if(workflow!=null)workflow.start();System.out.println("AI "+role+" listening on loopback port "+port+"; model="+AstraClient.MODEL);
    }
    /** Creates credentials once and writes each non-orchestrator only its required inbound/outbound identities. */
    static void initialize(Path runtime)throws Exception{
        Files.createDirectories(runtime);Path master=runtime.resolve("credentials.json");
        if(!Files.exists(master)){
            var random=new java.security.SecureRandom();var value=object();
            var names=new ArrayList<String>(List.of("analyst","policyAnalyst","outcomes"));for(var r:Role.values())names.add(r.name());
            for(String name:names){byte[] bytes=new byte[32];random.nextBytes(bytes);value.put(name,Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));}
            privateWrite(master,write(value));
        }
        var all=read(Files.readString(master));
        for(var role:Role.values()){
            var value=object();
            if(role==Role.ORCHESTRATOR)value=(com.fasterxml.jackson.databind.node.ObjectNode)all.deepCopy();
            else{value.set("ORCHESTRATOR",all.path("ORCHESTRATOR"));value.set(role.name(),all.path(role.name()));if(role==Role.RESEARCH)value.set("analyst",all.path("policyAnalyst"));}
            Path destination=runtime.resolve(role.name().toLowerCase(Locale.ROOT)+"-credentials.json");if(!Files.exists(destination))privateWrite(destination,write(value));
        }
        System.out.println("Local scoped AI credentials initialized; existing credentials were preserved.");
    }
    private static void privateWrite(Path path,String contents)throws Exception{
        Files.writeString(path,contents,StandardOpenOption.CREATE_NEW);
        var view=Files.getFileAttributeView(path,java.nio.file.attribute.PosixFileAttributeView.class);
        if(view!=null)view.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }
}

