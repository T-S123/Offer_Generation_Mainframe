package com.lending.engine.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Fixed owner endpoints, names and credential locations for the local bank picker. */
public enum BankTarget {
    EMMA("emma", "Emma Woodhouse Bank", 8090),
    ANNA("anna", "Anna Karenina Bank", 8290);

    public final String id;
    public final String name;
    public final int ownerPort;

    BankTarget(String id, String name, int ownerPort) {
        this.id = id;
        this.name = name;
        this.ownerPort = ownerPort;
    }

    public static List<BankTarget> choices() {
        return List.of(EMMA, ANNA);
    }

    public Path runtime(Path root) {
        Path runtime = root.resolve("runtime");
        return this == ANNA ? runtime.resolve("anna") : runtime;
    }

    public URI endpoint() {
        return URI.create("http://127.0.0.1:" + ownerPort + "/api/v1/");
    }

    /** Reads only this bank's owner token when an application session needs it. */
    public String token(Path root) throws IOException {
        String token = Files.readString(runtime(root).resolve("api-token")).trim();
        if (token.length() < 32) throw new IOException("Invalid bank owner credential");
        return token;
    }

    /** Resolves the runtime for the bank-bound process; source assets stay shared. */
    public static Path processRuntime(Path root) {
        return "anna".equals(System.getenv("LENDING_BANK")) ? ANNA.runtime(root) : EMMA.runtime(root);
    }
}
