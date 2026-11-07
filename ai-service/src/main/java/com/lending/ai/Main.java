package com.lending.ai;

import java.nio.file.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import static com.lending.ai.Json.*;
import static com.lending.ai.Contracts.*;

/** Hosts scoped agents or a keyless Performance API without workers; the compatibility API owns independent simulation, investigation and customer-personalization workers. */
public final class Main {
    private Main(){}
    public static void main(String[] args)throws Exception {
        boolean apiOnly=Boolean.parseBoolean(System.getenv().getOrDefault("AI_API_ONLY","false"));
        Network network=Network.parse(System.getenv().getOrDefault("AI_NETWORK","SIMULATION"));
        Role role=Role.valueOf(System.getenv().getOrDefault("AI_ROLE","ORCHESTRATOR"));require(network.roles().contains(role),"NETWORK_SCOPE","Role unavailable in this network");
        Path root=Path.of(System.getProperty("engine.home",".")).toAbsolutePath().normalize(),runtime=root.resolve("runtime/ai");
        if(args.length==1&&args[0].equals("--init")){initialize(runtime);return;}
        Path networkRuntime=network==Network.SIMULATION?runtime:runtime.resolve(network.name().toLowerCase(Locale.ROOT));
        Path credentialFile=Path.of(System.getenv().getOrDefault(network==Network.SIMULATION?"AI_CREDENTIAL_FILE":"AI_"+network.name()+"_CREDENTIAL_FILE",networkRuntime.resolve(role.name().toLowerCase(Locale.ROOT)+"-credentials.json").toString()));
        var auth=read(Files.readString(credentialFile));var credentials=new LinkedHashMap<String,String>();auth.fields().forEachRemaining(e->credentials.put(e.getKey(),e.getValue().asText()));
        require(credentials.containsKey("ORCHESTRATOR")&&credentials.containsKey(role.name())&&credentials.values().stream().allMatch(v->v.length()>=32)&&new HashSet<>(credentials.values()).size()==credentials.size(),"CREDENTIALS","Distinct strong service and analyst credentials required");
        int checkBase=Integer.parseInt(System.getenv().getOrDefault("AI_BASE_PORT","8100"));
        if(args.length==1&&args[0].equals("--check")){
            for(var n:Network.values()){var authFile=(n==Network.SIMULATION?runtime:runtime.resolve(n.name().toLowerCase(Locale.ROOT))).resolve("credentials.json");var keys=read(Files.readString(authFile));
                int base=networkBase(n,checkBase);
                for(var r:n.roles()){var health=new Http("http://127.0.0.1:"+(base+r.ordinal()),keys.path("ORCHESTRATOR").asText()).call("GET","api/v1/ai/health",null);
                    require(health.path("role").asText().equals(r.name())&&health.path("network").asText().equals(n.name()),"ROLE_MISMATCH","Health endpoint belongs to another network or role");}}
            System.out.println("Six simulation, five investigation and five customer-personalization agents are healthy.");return;
        }
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
        var store=new Store(url,user,password,(network==Network.SIMULATION?"ai_":"ai_"+network.name().toLowerCase(Locale.ROOT)+"_")+role.name().toLowerCase(Locale.ROOT));
        int simulationBase=Integer.parseInt(System.getenv().getOrDefault("AI_BASE_PORT","8100"));
        int basePort=networkBase(network,simulationBase),port=basePort+role.ordinal();
        var peers=new EnumMap<Role,A2AClient>(Role.class);for(var r:network.roles())peers.put(r,new A2AClient("http://127.0.0.1:"+(basePort+r.ordinal()),credentials.get("ORCHESTRATOR")));
        EngineGateway engine=null;WorkflowService workflow=null;InvestigationWorkflowService investigations=null;PublicationService publication=null;OutcomeService outcomes=null;PersonalizationWorkflowService personalization=null;
        if(network==Network.SIMULATION&&role==Role.ORCHESTRATOR){
            engine=new EngineGateway(System.getenv().getOrDefault("AI_ENGINE_URL","http://127.0.0.1:8090/api/v1/"),Files.readString(root.resolve("runtime/api-token")).trim());
            workflow=new WorkflowService(store,engine,peers);publication=new PublicationService(store,workflow,engine);
            var feedbackKeys=read(Files.readString(runtime.resolve("feedback/credentials.json")));var feedbackPeers=new EnumMap<Role,A2AClient>(Role.class);
            for(var r:Network.FEEDBACK.roles()){
                feedbackPeers.put(r,new A2AClient("http://127.0.0.1:"+(feedbackBase(simulationBase)+r.ordinal()),feedbackKeys.path("ORCHESTRATOR").asText()));
                credentials.put("FEEDBACK_"+r.name(),feedbackKeys.path(r.name()).asText());
            }
            investigations=new InvestigationWorkflowService(store,feedbackPeers);
            var customerKeys=read(Files.readString(runtime.resolve("personalization/credentials.json")));var customerPeers=new EnumMap<Role,A2AClient>(Role.class);
            for(var r:Network.PERSONALIZATION.roles()){customerPeers.put(r,new A2AClient("http://127.0.0.1:"+(networkBase(Network.PERSONALIZATION,simulationBase)+r.ordinal()),customerKeys.path("ORCHESTRATOR").asText()));credentials.put("PERSONALIZATION_"+r.name(),customerKeys.path(r.name()).asText());}
            personalization=new PersonalizationWorkflowService(store,customerPeers,engine);
            outcomes=new OutcomeService(store,new Http(System.getenv().getOrDefault("AI_MARKETING_URL","http://127.0.0.1:8092/api/v1/"),Files.readString(root.resolve("runtime/marketing-api-token")).trim()),Integer.parseInt(System.getenv().getOrDefault("AI_FOLLOW_UP_DAYS","14")));
        }
        var policies=network==Network.SIMULATION&&role==Role.RESEARCH?new PolicyIndex(Path.of(System.getenv().getOrDefault("AI_POLICY_DIR",root.resolve("policy-documents").toString())),store,Boolean.parseBoolean(System.getenv().getOrDefault("AI_ENABLE_OCR","false"))):null;
        var central=new Http("http://127.0.0.1:"+simulationBase,credentials.get(role.name()));WorkflowService wf=workflow;InvestigationWorkflowService feedbackRuntime=investigations;PersonalizationWorkflowService customerRuntime=personalization;
        var tools=new AgentToolbox(policies,new McpTools(Path.of(System.getenv().getOrDefault(network==Network.SIMULATION?"AI_MCP_CONFIG":"AI_"+network.name()+"_MCP_CONFIG",root.resolve("ai-service/config/mcp-servers.json").toString())),network),(request,key,tokens)->{
            if(wf!=null)wf.reserve(role,request,key,tokens);else central.call("POST","internal/budget",obj("request",request,"callId",key,"maxTokens",tokens));
        });
        tools.settlement((request,key,tokens)->{if(wf!=null)wf.settle(role,request,key,tokens);else central.call("POST","internal/usage",obj("request",request,"callId",key,"actualTokens",tokens));});
        if(role==Role.RESEARCH){String policyToken=Files.readString(runtime.resolve("policy-reader-token")).trim();
            if(network==Network.SIMULATION)credentials.put("policyReader",policyToken);
            else tools.policyReader(new Http("http://127.0.0.1:"+(simulationBase+Role.RESEARCH.ordinal()),policyToken));}
        if(network==Network.FEEDBACK)tools.investigation(central);if(network==Network.PERSONALIZATION)tools.personalization(central);
        var model=new AstraClient(System.getenv("OPENAI_API_KEY"));var a2a=new A2AService(role,store,new AgentRunner(network,role,model,tools).audit(store));
        var server=new Server(port,role,analyst,credentials,a2a,store,workflow,publication,outcomes,policies,network==Network.SIMULATION?new Http("http://127.0.0.1:"+(basePort+Role.RESEARCH.ordinal()),credentials.getOrDefault("policyAnalyst",credentials.get("analyst"))):null,investigations);
        server.agentsEnabled(!apiOnly);server.personalization(personalization);
        if(network==Network.SIMULATION&&role==Role.ORCHESTRATOR&&System.getenv("AI_OUTCOME_SOURCE_ID")!=null)server.sourceFacts(new SourceFacts(store,System.getenv("AI_OUTCOME_SOURCE_ID"),System.getenv().getOrDefault("AI_OUTCOME_SOURCE_MODE","OBSERVED_REAL"),Integer.parseInt(System.getenv().getOrDefault("AI_FOLLOW_UP_DAYS","14"))));
        var maintenance=Executors.newSingleThreadScheduledExecutor();OutcomeService outcome=outcomes;
        if(policies!=null)maintenance.scheduleWithFixedDelay(()->{try{policies.scan();}catch(Exception e){System.err.println("Policy refresh failed: "+e.getClass().getSimpleName());}},0,60,TimeUnit.SECONDS);
        if(outcomes!=null)maintenance.scheduleWithFixedDelay(()->{try{outcome.synchronize();server.feedback().monitor();}catch(Exception e){System.err.println("Outcome refresh pending: "+e.getClass().getSimpleName());}},10,3600,TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(()->{server.close();if(wf!=null)wf.close();if(feedbackRuntime!=null)feedbackRuntime.close();if(customerRuntime!=null)customerRuntime.close();maintenance.shutdownNow();a2a.close();model.close();store.close();}));
        server.start();if(!apiOnly&&workflow!=null)workflow.start();if(!apiOnly&&investigations!=null)investigations.start();if(!apiOnly&&personalization!=null)personalization.start();System.out.println("AI "+network+" "+role+" listening on loopback port "+port+"; model="+AstraClient.MODEL);
    }
    static int networkBase(Network network,int base){return network==Network.SIMULATION?base:network==Network.FEEDBACK?feedbackBase(base):Integer.parseInt(System.getenv().getOrDefault("AI_PERSONALIZATION_BASE_PORT",Integer.toString(base+20)));}
    private static int feedbackBase(int simulationBase){return Integer.parseInt(System.getenv().getOrDefault("AI_FEEDBACK_BASE_PORT",Integer.toString(simulationBase+10)));}
    /** Preserves existing credentials and provisions distinct feedback and customer groups plus read-only policy access. */
    static void initialize(Path runtime)throws Exception{
        initializeGroup(runtime,Network.SIMULATION);initializeGroup(runtime.resolve("feedback"),Network.FEEDBACK);initializeGroup(runtime.resolve("personalization"),Network.PERSONALIZATION);
        Path reader=runtime.resolve("policy-reader-token");if(!Files.exists(reader))privateWrite(reader,token());
        System.out.println("Simulation, feedback and personalization credentials initialized; existing credentials were preserved.");
    }
    private static String token(){byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static void initializeGroup(Path runtime,Network network)throws Exception{
        Files.createDirectories(runtime);Path master=runtime.resolve("credentials.json");
        if(!Files.exists(master)){
            var random=new java.security.SecureRandom();var value=object();
            var names=new ArrayList<String>(List.of("analyst","policyAnalyst","outcomes"));for(var r:network.roles())names.add(r.name());
            for(String name:names){byte[] bytes=new byte[32];random.nextBytes(bytes);value.put(name,Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));}
            privateWrite(master,write(value));
        }
        var all=(com.fasterxml.jackson.databind.node.ObjectNode)read(Files.readString(master));boolean added=false;
        for(var r:network.roles())if(!all.hasNonNull(r.name())){all.put(r.name(),token());added=true;}
        if(added)replacePrivate(master,write(all));
        for(var role:network.roles()){
            var value=object();
            if(role==Role.ORCHESTRATOR)value=(com.fasterxml.jackson.databind.node.ObjectNode)all.deepCopy();
            else{value.set("ORCHESTRATOR",all.path("ORCHESTRATOR"));value.set(role.name(),all.path(role.name()));if(role==Role.RESEARCH)value.set("analyst",all.path("policyAnalyst"));}
            Path destination=runtime.resolve(role.name().toLowerCase(Locale.ROOT)+"-credentials.json");if(!Files.exists(destination))privateWrite(destination,write(value));else{var existing=(com.fasterxml.jackson.databind.node.ObjectNode)read(Files.readString(destination));boolean changed=false;var names=value.fieldNames();while(names.hasNext()){String name=names.next();if(!existing.has(name)){existing.set(name,value.path(name));changed=true;}}if(changed)replacePrivate(destination,write(existing));}
        }
        System.out.println("Local scoped AI credentials initialized; existing credentials were preserved.");
    }
    /** Extends a credential set atomically while retaining private permissions and all existing keys. */
    private static void replacePrivate(Path path,String contents)throws Exception{
        Path temporary=Files.createTempFile(path.getParent(),"credentials-",".tmp");
        try{var view=Files.getFileAttributeView(temporary,java.nio.file.attribute.PosixFileAttributeView.class);if(view!=null)view.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));Files.writeString(temporary,contents);try{Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temporary);}
    }
    private static void privateWrite(Path path,String contents)throws Exception{
        Files.writeString(path,contents,StandardOpenOption.CREATE_NEW);
        var view=Files.getFileAttributeView(path,java.nio.file.attribute.PosixFileAttributeView.class);
        if(view!=null)view.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }
}

