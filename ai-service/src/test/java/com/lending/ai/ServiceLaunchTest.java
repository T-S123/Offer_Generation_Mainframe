package com.lending.ai;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.lending.ai.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies independent network processes, legacy routing and bank-specific recorded outcomes using test-owned services. */
@Timeout(150)
class ServiceLaunchTest {
    @TempDir Path root;
    @Test void feedbackProcessesReadOnlyTheirBanksPublicationRegistry()throws Exception{
        var processes=new ArrayList<Process>();
        try(var pg=EmbeddedPostgres.start();var connection=pg.getPostgresDatabase().getConnection()){
            int first=availablePorts(),second;
            do{second=availablePorts();}while(Math.abs(second-first)<30);
            var ports=Map.of("emma",first,"anna",second);
            var clients=new HashMap<String,Http>();
            for(var bank:List.of("emma","anna")){
                String database=bank+"_scope";
                try(var ddl=connection.createStatement()){ddl.execute("CREATE DATABASE "+database);}
                String url="jdbc:postgresql://127.0.0.1:"+pg.getPort()+"/"+database;
                try(var store=new Store(url,"postgres","","ai_orchestrator")){
                    store.create("PUBLICATION","shared-catalog-id",obj("analyst","local-analyst","marker",bank+" projection"));
                    store.create("PUBLICATION",bank+"-publication",obj("analyst","local-analyst","marker",bank+" record"));
                }
                Path runtime=root.resolve(bank.equals("anna")?"runtime/anna":"runtime");
                Main.initialize(runtime.resolve("ai"));
                Files.writeString(runtime.resolve("api-token"),bank+"-engine-token");
                Files.writeString(runtime.resolve("marketing-api-token"),bank+"-offer-token");
                int base=ports.get(bank);
                String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
                var builder=new ProcessBuilder(java,"-Dengine.home="+root,"-cp",System.getProperty("java.class.path"),Main.class.getName());
                var env=builder.environment();
                for(String key:List.of("OPENAI_API_KEY","AI_FEEDBACK_BASE_PORT","AI_CREDENTIAL_FILE","AI_FEEDBACK_CREDENTIAL_FILE","AI_OUTCOME_SOURCE_ID","AI_ANALYST_ID"))env.remove(key);
                env.put("LENDING_BANK",bank);env.put("AI_NETWORK","FEEDBACK");env.put("AI_ROLE","ORCHESTRATOR");
                env.put("AI_API_ONLY","true");env.put("AI_BASE_PORT",Integer.toString(base));
                env.put("AI_DATABASE_URL",url);env.put("AI_DB_USER","postgres");env.put("AI_DB_PASSWORD","");
                processes.add(builder.redirectErrorStream(true).redirectOutput(root.resolve(bank+"-bank.log").toFile()).start());
                clients.put(bank,new Http("http://127.0.0.1:"+(base+10),Files.readString(runtime.resolve("ai/feedback/analyst-token")).trim()));
            }
            for(var bank:clients.keySet()){
                var client=clients.get(bank);
                awaitState(client,"api/v1/ai/health","/status","UP");
                var publications=client.call("GET","api/v1/ai/performance/publications",null);
                assertEquals(2,publications.size());
                assertTrue(publications.toString().contains(bank+" projection"));
                assertFalse(publications.toString().contains((bank.equals("anna")?"emma":"anna")+" projection"));
                assertTrue(publications.toString().contains(bank+"-publication"));
            }
        }finally{
            for(var process:processes)process.destroy();
            for(var process:processes)if(!process.waitFor(5,TimeUnit.SECONDS))process.destroyForcibly();
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void networksOrPerformanceOnlyStartWithoutProviderCalls(boolean apiOnly)throws Exception{
        var processes=new ArrayList<Process>();
        var owner=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        owner.createContext("/api/v1/",exchange->{
            try {
                Object response=switch(exchange.getRequestURI().getPath()){
                    case "/api/v1/business/catalog" -> obj("offers",List.of());
                    case "/api/v1/customers/customer" -> obj("profiles",List.of(obj("version",2)));
                    case "/api/v1/offer-creation/customers/customer/personalization-sessions" -> obj("active",true);
                    case "/api/v1/offer-creation/customers/customer/personalization-context" -> obj("pipeline",obj("state","NO_ELIGIBLE_OFFERS"));
                    default -> null;
                };
                Http.send(exchange,response==null?404:200,response==null?obj("error","Fixture route missing"):response);
            } finally { exchange.close(); }
        });
        owner.start();
        try(var pg=EmbeddedPostgres.start();var connection=pg.getPostgresDatabase().getConnection()){
            Main.initialize(root.resolve("runtime/ai"));Files.writeString(root.resolve("runtime/api-token"),"test-engine-token");
            Files.writeString(root.resolve("runtime/marketing-api-token"),"test-storage-token");
            String url=connection.getMetaData().getURL(),user=connection.getMetaData().getUserName();int base=availablePorts();
            String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
            String jar=System.getProperty("ai.packagedJar");
            for(var network:Network.values())for(var role:network.roles()){
                if(apiOnly&&(network==Network.PERSONALIZATION||role!=Contracts.Role.ORCHESTRATOR))continue;
                var args=new ArrayList<String>(List.of(java,"-Xmx256m","-Dengine.home="+root));
                if(jar!=null)args.addAll(List.of("-jar",jar));else args.addAll(List.of("-cp",System.getProperty("java.class.path"),Main.class.getName()));
                var builder=new ProcessBuilder(args);var env=builder.environment();env.remove("OPENAI_API_KEY");env.remove("AI_API_ONLY");env.remove("AI_CREDENTIAL_FILE");env.remove("AI_FEEDBACK_CREDENTIAL_FILE");env.remove("AI_OUTCOME_SOURCE_ID");env.remove("AI_FEEDBACK_BASE_PORT");env.remove("AI_FEEDBACK_MCP_CONFIG");env.remove("AI_PERSONALIZATION_BASE_PORT");env.remove("AI_PERSONALIZATION_CREDENTIAL_FILE");env.remove("AI_PERSONALIZATION_MCP_CONFIG");env.put("AI_NETWORK",network.name());env.put("AI_API_ONLY",Boolean.toString(apiOnly));
                env.put("AI_ROLE",role.name());env.put("AI_DATABASE_URL",url);env.put("AI_DB_USER",user);env.put("AI_DB_PASSWORD","");env.put("AI_BASE_PORT",Integer.toString(base));
                env.put("AI_POLICY_DIR",root.resolve("policies").toString());env.put("AI_MCP_CONFIG",root.resolve("empty-mcp.json").toString());
                env.put("AI_ENGINE_URL","http://127.0.0.1:"+owner.getAddress().getPort()+"/api/v1/");
                env.put("AI_MARKETING_URL",env.get("AI_ENGINE_URL"));
                processes.add(builder.redirectErrorStream(true).redirectOutput(root.resolve(network.name()+"-"+role.name()+".log").toFile()).start());
            }
            int processIndex=0;
            for(var network:Network.values())for(var role:network.roles()){
                if(apiOnly&&(network==Network.PERSONALIZATION||role!=Contracts.Role.ORCHESTRATOR))continue;
                String token=read(Files.readString(root.resolve(network==Network.SIMULATION?"runtime/ai/credentials.json":"runtime/ai/"+network.name().toLowerCase(Locale.ROOT)+"/credentials.json"))).path("ORCHESTRATOR").asText();
                boolean ready=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
                while(System.nanoTime()<deadline){
                    assertTrue(processes.get(processIndex).isAlive(),()->"Startup failed for "+network+" "+role+": "+log(network,role));
                    try{var health=new Http("http://127.0.0.1:"+(Main.networkBase(network,base)+role.ordinal()),token).call("GET","api/v1/ai/health",null);
                        assertEquals(network.name(),health.path("network").asText());assertEquals(role.name(),health.path("role").asText());assertEquals("gpt-6-astra",health.path("model").asText());assertFalse(health.path("modelConfigured").asBoolean());assertEquals(!apiOnly,health.path("agentsEnabled").asBoolean());ready=true;break;
                    }catch(Fault unavailable){Thread.sleep(100);}
                }
                assertTrue(ready,network+" "+role.name()+" never became healthy");processIndex++;
            }
            var simulation=analystApi(Network.SIMULATION,base);
            var feedback=analystApi(Network.FEEDBACK,base);
            assertTrue(feedback.call("GET","api/v1/ai/performance/publications",null).isEmpty());
            if(apiOnly){
                assertEquals("AGENTS_DISABLED",assertThrows(Fault.class,()->feedback.call("POST","api/v1/ai/feedback/briefings",obj("requestId","disabled"))).code);
            }else{
                var legacy=simulation.call("POST","api/v1/ai/feedback/briefings",obj("requestId","legacy"));
                assertEquals("FEEDBACK",simulation.call("GET","api/v1/ai/workflows/"+legacy.path("id").asText(),null).at("/workflow/network").asText());
                var simulationProcesses=processes.subList(0,Network.SIMULATION.roles().size());
                for(var process:simulationProcesses)process.destroy();
                for(var process:simulationProcesses)assertTrue(process.waitFor(10,TimeUnit.SECONDS));
                var briefing=feedback.call("POST","api/v1/ai/feedback/briefings",obj("requestId","standalone"));
                awaitState(feedback,"api/v1/ai/feedback/workflows/"+briefing.path("id").asText(),"/workflow/state","COMPLETED");
                var personalization=analystApi(Network.PERSONALIZATION,base);
                String route="api/v1/ai/customer-personalization/customer";
                String session="standalone-session";
                var started=personalization.call("POST",route,obj("sessionId",session,"profileVersion",2));
                awaitState(personalization,route+"/"+started.path("id").asText()+"?sessionId="+session,"/state","NO_QUALIFIED_IMPROVEMENT");
                assertEquals("AUTHENTICATION",assertThrows(Fault.class,()->new Http(
                    "http://127.0.0.1:"+Main.networkBase(Network.PERSONALIZATION,base),analystToken(Network.FEEDBACK))
                    .call("GET","api/v1/ai/health",null)).code);
            }
        }finally{
            for(var process:processes)process.destroy();
            for(var process:processes)if(!process.waitFor(5,TimeUnit.SECONDS))process.destroyForcibly();
            owner.stop(0);
        }
    }
    private Http analystApi(Network network,int base)throws Exception{
        return new Http("http://127.0.0.1:"+Main.networkBase(network,base),analystToken(network));
    }
    private String analystToken(Network network)throws Exception{
        Path runtime=root.resolve("runtime/ai");
        if(network!=Network.SIMULATION)runtime=runtime.resolve(network.name().toLowerCase(Locale.ROOT));
        return Files.readString(runtime.resolve("analyst-token")).trim();
    }
    private static void awaitState(Http client,String route,String pointer,String expected)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline){
            try{
                var state=client.call("GET",route,null).at(pointer).asText();
                if(expected.equals(state))return;
                assertFalse(Set.of("FAILED","PAUSED_BUDGET").contains(state),state);
            }catch(Fault unavailable){if(!unavailable.code.equals("DEPENDENCY_UNCERTAIN"))throw unavailable;}
            Thread.sleep(100);
        }
        fail("Workflow did not reach "+expected);
    }
    private String log(Network network,Contracts.Role role){try{return Files.readString(root.resolve(network.name()+"-"+role.name()+".log"));}catch(Exception e){return "Log unavailable";}}
    private static int availablePorts()throws Exception{
        for(int attempt=0;attempt<100;attempt++){int base=20000+new Random().nextInt(10000);var ports=new ArrayList<ServerSocket>();
            try{for(int i=0;i<29;i++)ports.add(new ServerSocket(base+i,1,java.net.InetAddress.getLoopbackAddress()));return base;}
            catch(java.io.IOException ignored){}finally{for(var p:ports)p.close();}
        }throw new IllegalStateException("No free network port ranges found");
    }
}
