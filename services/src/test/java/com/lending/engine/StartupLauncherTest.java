/** Verifies launcher readiness, cold-probe tolerance and cleanup using isolated stand-in processes. */
package com.lending.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the real launcher against controlled executables without binding ports or accessing application databases. */
@Timeout(30)
class StartupLauncherTest {
    @TempDir Path temp;

    /** Requires all three APIs to answer before advertising readiness and tolerates a two-second cold response. */
    @Test void waitsForMarketingAndAllowsColdHealthResponses() throws Exception {
        Process process=start(false);
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
        Process process=start(true);
        try {
            assertTrue(process.waitFor(15,TimeUnit.SECONDS));assertNotEquals(0,process.exitValue());
            String output=Files.readString(temp.resolve("launcher.log"));
            assertTrue(output.contains("Marketing service failed to start."),output);
            assertFalse(output.contains("All three APIs are responding."),output);
        } finally {stop(process);}
        assertChildrenStopped();
    }

    /** Copies the production launcher into a temporary project and supplies controlled Java and curl commands. */
    private Process start(boolean failMarketing) throws Exception {
        Files.createDirectories(temp.resolve("scripts"));Files.createDirectories(temp.resolve("runtime"));Files.createDirectories(temp.resolve("bin"));
        Files.copy(Path.of("../scripts/run.sh"),temp.resolve("scripts/run.sh"));
        for(String token:new String[]{"bureau-api-token","marketing-api-token","marketing-source-token"})Files.writeString(temp.resolve("runtime/"+token),"test-only");
        executable("java","""
            #!/usr/bin/env bash
            # Holds a controlled service process or simulates marketing startup failure.
            role=engine
            [[ "$*" == *--credit-service* ]] && role=credit
            [[ "$*" == *marketing-offers* ]] && role=marketing
            echo "$BASHPID" >"$role.pid"
            if [[ "$role" == marketing && "$FAIL_MARKETING" == yes ]]; then exit 23; fi
            exec sleep 25
            """);
        executable("curl","""
            #!/usr/bin/env bash
            # Simulates a cold engine response and delayed marketing readiness without network access.
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
        var builder=new ProcessBuilder("bash",temp.resolve("scripts/run.sh").toString()).directory(temp.toFile());
        builder.environment().put("PATH",temp.resolve("bin")+":"+System.getenv("PATH"));
        builder.environment().put("DATABASE_URL","unused-test-database");builder.environment().put("FAIL_MARKETING",failMarketing?"yes":"no");
        return builder.redirectErrorStream(true).redirectOutput(temp.resolve("launcher.log").toFile()).start();
    }

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

    /** Confirms the production cleanup trap terminated every recorded fixture service. */
    private void assertChildrenStopped() throws Exception {
        for(String role:new String[]{"credit","engine","marketing"}){
            Path pid=temp.resolve(role+".pid");if(Files.exists(pid))assertFalse(ProcessHandle.of(Long.parseLong(Files.readString(pid).trim())).map(ProcessHandle::isAlive).orElse(false),role);
        }
    }
}
