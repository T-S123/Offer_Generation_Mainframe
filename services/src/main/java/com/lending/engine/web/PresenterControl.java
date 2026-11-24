package com.lending.engine.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.lending.engine.infrastructure.Json;
import com.lending.engine.infrastructure.BankTarget;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Local maintenance command port. The browser supplies only a validated action and idempotency key. */
interface PresenterControl {
    JsonNode invoke(String... args) throws Exception;
    boolean maintenance();
    String epoch();

    /** Binds maintenance commands and status to the selected bank's fixed runtime. */
    static PresenterControl local(Path root,BankTarget bank) {
        Path control = bank.runtime(root).resolve("presenter");
        return new PresenterControl() {
            public JsonNode invoke(String... args) throws Exception {
                var command = new ArrayList<>(List.of("bash", root.resolve("scripts/presenter-command.sh").toString(),bank.id));
                command.addAll(List.of(args));
                var process = new ProcessBuilder(command).directory(root.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
                if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Presenter coordinator unavailable"); }
                byte[] result = process.getInputStream().readNBytes(16385);
                if(process.exitValue()!=0 || result.length>16384) throw new IllegalStateException("Presenter command failed");
                return Json.MAPPER.readTree(new String(result, StandardCharsets.UTF_8));
            }
            public boolean maintenance() { return Files.exists(control.resolve("maintenance")); }
            public String epoch() {
                try { return Files.readString(control.resolve("epoch")); }
                catch (java.io.IOException e) { return ""; }
            }
        };
    }
}
