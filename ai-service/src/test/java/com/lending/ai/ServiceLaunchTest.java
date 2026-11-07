package com.lending.ai;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.lending.ai.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/** Starts all three isolated agent groups or the model-free Performance API on disposable PostgreSQL using ports below the Linux ephemeral range, without provider calls. */
@Timeout(150)
class ServiceLaunchTest {
    @TempDir Path root;
    @ParameterizedTest @ValueSource(booleans={false,true}) void networksOrPerformanceOnlyStartWithoutProviderCalls(boolean apiOnly)throws Exception{
        var processes=new ArrayList<Process>();
        try(var pg=EmbeddedPostgres.start();var connection=pg.getPostgresDatabase().getConnection()){
            Main.initialize(root.resolve("runtime/ai"));Files.writeString(root.resolve("runtime/api-token"),"test-engine-token");
            Files.writeString(root.resolve("runtime/marketing-api-token"),"test-storage-token");
            String url=connection.getMetaData().getURL(),user=connection.getMetaData().getUserName();int base=availablePorts();
            String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
            String jar=System.getProperty("ai.packagedJar");
            for(var network:Network.values())for(var role:network.roles()){
                if(apiOnly&&(network!=Network.SIMULATION||role!=Contracts.Role.ORCHESTRATOR))continue;
                var args=new ArrayList<String>(List.of(java,"-Xmx256m","-Dengine.home="+root));
                if(jar!=null)args.addAll(List.of("-jar",jar));else args.addAll(List.of("-cp",System.getProperty("java.class.path"),Main.class.getName()));
                var builder=new ProcessBuilder(args);var env=builder.environment();env.remove("OPENAI_API_KEY");env.remove("AI_API_ONLY");env.remove("AI_CREDENTIAL_FILE");env.remove("AI_FEEDBACK_CREDENTIAL_FILE");env.remove("AI_OUTCOME_SOURCE_ID");env.remove("AI_FEEDBACK_BASE_PORT");env.remove("AI_FEEDBACK_MCP_CONFIG");env.remove("AI_PERSONALIZATION_BASE_PORT");env.remove("AI_PERSONALIZATION_CREDENTIAL_FILE");env.remove("AI_PERSONALIZATION_MCP_CONFIG");env.put("AI_NETWORK",network.name());env.put("AI_API_ONLY",Boolean.toString(apiOnly));
                env.put("AI_ROLE",role.name());env.put("AI_DATABASE_URL",url);env.put("AI_DB_USER",user);env.put("AI_DB_PASSWORD","");env.put("AI_BASE_PORT",Integer.toString(base));
                env.put("AI_POLICY_DIR",root.resolve("policies").toString());env.put("AI_MCP_CONFIG",root.resolve("empty-mcp.json").toString());
                processes.add(builder.redirectErrorStream(true).redirectOutput(root.resolve(network.name()+"-"+role.name()+".log").toFile()).start());
            }
            int processIndex=0;
            for(var network:Network.values())for(var role:network.roles()){
                if(apiOnly&&(network!=Network.SIMULATION||role!=Contracts.Role.ORCHESTRATOR))continue;
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
        }finally{for(var process:processes)process.destroy();for(var process:processes)if(!process.waitFor(5,TimeUnit.SECONDS))process.destroyForcibly();}
    }
    private String log(Network network,Contracts.Role role){try{return Files.readString(root.resolve(network.name()+"-"+role.name()+".log"));}catch(Exception e){return "Log unavailable";}}
    private static int availablePorts()throws Exception{
        for(int attempt=0;attempt<100;attempt++){int base=20000+new Random().nextInt(10000);var ports=new ArrayList<ServerSocket>();
            try{for(int i=0;i<29;i++)ports.add(new ServerSocket(base+i,1,java.net.InetAddress.getLoopbackAddress()));return base;}
            catch(java.io.IOException ignored){}finally{for(var p:ports)p.close();}
        }throw new IllegalStateException("No free network port ranges found");
    }
}
