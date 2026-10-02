/** Verifies launcher ownership, duplicate rejection, readiness and cleanup with isolated stand-in processes. */
package com.lending.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Runs both production launchers with controlled Java, HTTP and socket inspection, without application data. */
@Timeout(30)
class StartupLauncherTest {
    @TempDir Path temp;

    /** Requires all three APIs to answer before advertising readiness and tolerates a two-second cold response. */
    @Test void waitsForMarketingAndAllowsColdHealthResponses() throws Exception {
        prepare();Process process=launch("run.sh","launcher.log",Map.of());
        try {
            awaitText("All three APIs are responding.");
            String probes=Files.readString(temp.resolve("probes"));
            assertTrue(probes.contains(":8091/api/v1/credit/health"));
            assertTrue(probes.contains(":8090/api/v1/health"));
            assertTrue(probes.contains(":8092/api/v1/health"));
            assertTrue(Files.exists(temp.resolve("marketing-ready")));
            assertTrue(Files.readString(temp.resolve("launcher.log")).contains("02 Business for simulations"));
        } finally {stop(process);}
        assertChildrenStopped();
    }

    /** Fails startup when marketing exits instead of printing a misleading readiness message. */
    @Test void marketingStartupFailureStopsOnlyOwnedProcesses() throws Exception {
        prepare();Process process=launch("run.sh","launcher.log",Map.of("FAIL_MARKETING","yes"));
        try {
            assertTrue(process.waitFor(15,TimeUnit.SECONDS));assertNotEquals(0,process.exitValue());
            String output=Files.readString(temp.resolve("launcher.log"));
            assertTrue(output.contains("Marketing service failed to start."),output);
            assertFalse(output.contains("All three APIs are responding."),output);
        } finally {stop(process);}
        assertChildrenStopped();
    }

    /** Duplicate launches must preserve the first group's processes/logs; AI checks remain available while running. */
    @ParameterizedTest @ValueSource(strings={"run.sh","run-ai.sh"})
    void duplicateLaunchLeavesOriginalServicesAndLogsAlone(String script) throws Exception {
        prepare();Process original=launch(script,"launcher.log",Map.of());
        try {
            awaitText(ready(script));
            Path log=temp.resolve(script.equals("run.sh")?"runtime/credit-service.log":"runtime/ai/orchestrator.log");
            Files.writeString(log,"retained log");
            Process duplicate=launch(script,"duplicate.log",Map.of());
            try {
                assertTrue(duplicate.waitFor(5,TimeUnit.SECONDS));assertNotEquals(0,duplicate.exitValue());
                String output=Files.readString(temp.resolve("duplicate.log"));
                assertTrue(output.contains("already running for this project"),output);
                assertFalse(output.contains(ready(script)),output);
                assertEquals("retained log",Files.readString(log));
                assertTrue(original.isAlive());assertChildrenAlive();
            } finally {stop(duplicate);}
            if(script.equals("run-ai.sh")){
                Process check=launch(script,"check.log",Map.of(),"--check");
                try{assertTrue(check.waitFor(5,TimeUnit.SECONDS));assertEquals(0,check.exitValue());}
                finally{stop(check);}
            }
        } finally {stop(original);}
        assertChildrenStopped();
        Process restarted=launch(script,"launcher.log",Map.of());
        try{awaitText(ready(script));}finally{stop(restarted);}
        assertChildrenStopped();
    }

    /** Existing listeners, including an older launcher without a lock, must be rejected before Java or log truncation. */
    @ParameterizedTest @ValueSource(strings={"run.sh","run-ai.sh"})
    void occupiedPortFailsBeforeStartingAnyChild(String script) throws Exception {
        prepare();Path log=temp.resolve(script.equals("run.sh")?"runtime/credit-service.log":"runtime/ai/orchestrator.log");
        Files.writeString(log,"old service log");
        Process process=launch(script,"launcher.log",Map.of("OCCUPIED_PORT",script.equals("run.sh")?"8090":"18103"));
        try {
            assertTrue(process.waitFor(5,TimeUnit.SECONDS));assertNotEquals(0,process.exitValue());
            String output=Files.readString(temp.resolve("launcher.log"));
            assertTrue(output.contains("already in use"),output);assertFalse(output.contains(ready(script)),output);
            assertEquals("old service log",Files.readString(log));assertFalse(Files.exists(temp.resolve("initialized")));
            try(var paths=Files.list(temp)){assertFalse(paths.anyMatch(p->p.toString().endsWith(".pid")));}
        }finally{stop(process);}
    }

    /** A healthy response from another process cannot hide the new child's startup failure. */
    @ParameterizedTest @ValueSource(strings={"run.sh","run-ai.sh"})
    void wrongListenerOwnerCannotProduceReadiness(String script) throws Exception {
        prepare();Process process=launch(script,"launcher.log",Map.of("WRONG_OWNER","yes"));
        try {
            assertTrue(process.waitFor(15,TimeUnit.SECONDS));assertNotEquals(0,process.exitValue());
            String output=Files.readString(temp.resolve("launcher.log"));
            assertFalse(output.contains(ready(script)),output);
            assertTrue(output.contains(script.equals("run.sh")?"Engine failed to start.":"An AI service exited."),output);
        }finally{stop(process);}
        assertChildrenStopped();
    }

    /** Copies production scripts and supplies controlled commands, with AI ports intentionally changed from defaults. */
    private void prepare() throws Exception {
        for(String dir:List.of("scripts","runtime/ai","bin","ai-service/target"))Files.createDirectories(temp.resolve(dir));
        for(String script:List.of("run.sh","run-ai.sh","startup-guard.sh"))
            Files.copy(Path.of("../scripts",script),temp.resolve("scripts/"+script));
        Files.writeString(temp.resolve("ai-service/target/ai-simulation-1.0.0.jar"),"fixture");
        for(String token:List.of("api-token","bureau-api-token","marketing-api-token","marketing-source-token"))
            Files.writeString(temp.resolve("runtime/"+token),"test-only");
        executable("java","""
            #!/usr/bin/env bash
            # Holds a fixture child, handles AI check/init, or reproduces a child dying after stale health.
            if [[ "$*" == *--init* ]]; then touch initialized; exit 0; fi
            if [[ "$*" == *--check* ]]; then exit 0; fi
            role=engine
            [[ "$*" == *--credit-service* ]] && role=credit
            [[ "$*" == *marketing-offers* ]] && role=marketing
            [[ -z "$AI_ROLE" ]] || role="${AI_ROLE,,}"
            echo "$BASHPID" >"$role.pid"
            if [[ "$role" == marketing && "$FAIL_MARKETING" == yes ]]; then exit 23; fi
            if [[ "$WRONG_OWNER" == yes && ( "$role" == engine || "$role" == orchestrator ) ]]; then exec sleep 3; fi
            exec sleep 25
            """);
        executable("ss","""
            #!/usr/bin/env bash
            # Reports fixture listeners only after their child starts, or simulates an existing foreign owner.
            port="${@: -1}"; port="${port##*:}"
            if [[ "$port" == "$OCCUPIED_PORT" ]]; then echo "LISTEN pid=1,"; exit 0; fi
            case "$port" in
                8090|2323) role=engine;; 8091) role=credit;; 8092) role=marketing;;
                18100) role=orchestrator;; 18101) role=research;; 18102) role=designer;;
                18103) role=coordinator;; 18104) role=analyzer;; 18105) role=reflection;;
                *) exit 1;;
            esac
            [[ -s "$role.pid" ]] || exit 0
            pid=$(cat "$role.pid")
            kill -0 "$pid" 2>/dev/null || exit 0
            if [[ "$WRONG_OWNER" == yes && ( "$role" == engine || "$role" == orchestrator ) ]]; then pid=1; fi
            echo "LISTEN pid=$pid,"
            """);
        executable("curl","""
            #!/usr/bin/env bash
            # Supplies healthy APIs, with a cold engine response and delayed marketing readiness.
            timeout=0
            while (( $# )); do
                if [[ "$1" == --max-time ]]; then timeout="$2"; shift 2; else url="$1"; shift; fi
            done
            echo "$url" >>probes
            if [[ "$url" == *:8090/* ]]; then
                (( timeout >= 2 )) || exit 28
                sleep 2
            fi
            if [[ "$url" == *:8092/* ]]; then
                [[ "$FAIL_MARKETING" == yes ]] && exit 7
                if [[ ! -f marketing-probed ]]; then touch marketing-probed; exit 7; fi
                touch marketing-ready
            fi
            exit 0
            """);
    }

    /** Starts a real launcher in the temporary project with fixture dependencies and no live credentials. */
    private Process launch(String script,String log,Map<String,String> options,String... extra) throws Exception {
        var args=new ArrayList<String>(List.of("bash",temp.resolve("scripts/"+script).toString()));args.addAll(List.of(extra));
        var builder=new ProcessBuilder(args).directory(temp.toFile());var env=builder.environment();
        env.put("PATH",temp.resolve("bin")+":"+System.getenv("PATH"));env.put("DATABASE_URL","unused-test-database");
        env.put("OPENAI_API_KEY","unused-test-key");env.put("AI_BASE_PORT","18100");env.remove("AI_ROLE");
        for(String key:List.of("FAIL_MARKETING","WRONG_OWNER","OCCUPIED_PORT"))env.put(key,"");
        env.putAll(options);
        return builder.redirectErrorStream(true).redirectOutput(temp.resolve(log).toFile()).start();
    }

    /** Returns the user-visible readiness message for the selected service group. */
    private String ready(String script){return script.equals("run.sh")?"All three APIs are responding.":"Six AI agents are ready.";}

    /** Writes an executable fixture command within this test's temporary directory. */
    private void executable(String name,String script) throws Exception {
        Path file=temp.resolve("bin/"+name);Files.writeString(file,script);assertTrue(file.toFile().setExecutable(true));
    }

    /** Waits briefly for a specific launcher message and exposes its log if readiness never arrives. */
    private void awaitText(String text) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline){if(Files.readString(temp.resolve("launcher.log")).contains(text))return;Thread.sleep(100);}
        fail(Files.readString(temp.resolve("launcher.log")));
    }

    /** Stops the owned launcher and any fixture descendants left after a failed assertion. */
    private void stop(Process process) throws Exception {
        var children=process.descendants().toList();process.destroy();
        if(!process.waitFor(5,TimeUnit.SECONDS)){children.forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();process.waitFor();}
    }

    /** Confirms a rejected duplicate did not kill any child of the original launcher. */
    private void assertChildrenAlive() throws Exception {assertChildren(true);}

    /** Confirms cleanup terminated every recorded fixture service, releasing the singleton lock. */
    private void assertChildrenStopped() throws Exception {assertChildren(false);}

    /** Compares every recorded fixture PID with the expected process lifecycle state. */
    private void assertChildren(boolean expected) throws Exception {
        try(var files=Files.list(temp)){
            for(Path pid:files.filter(p->p.toString().endsWith(".pid")).toList())
                assertEquals(expected,ProcessHandle.of(Long.parseLong(Files.readString(pid).trim())).map(ProcessHandle::isAlive).orElse(false),pid.toString());
        }
    }
}
