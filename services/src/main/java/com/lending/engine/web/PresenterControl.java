package com.lending.engine.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.lending.engine.infrastructure.Json;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Local maintenance command port. The browser supplies only a validated action and idempotency key. */
interface PresenterControl {
    JsonNode invoke(String... args) throws Exception;
    boolean maintenance();
    String epoch();

    /** Uses fixed process arguments and the checkout's coordinator; no browser path or shell is evaluated. */
    static PresenterControl local(Path root) {
        Path control = root.resolve("runtime/presenter");
        return new PresenterControl() {
            public JsonNode invoke(String... args) throws Exception {
                var command = new ArrayList<>(List.of("python3", root.resolve("scripts/presenter-reset.py").toString()));
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
