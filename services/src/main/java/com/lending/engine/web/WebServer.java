package com.lending.engine.web;

import com.lending.engine.infrastructure.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Loopback browser adapter for the existing owner APIs. Demo sessions select a customer or Business
 * workspace, including isolated customer personalization and separate Business network routes. Credentials and lending decisions
 * stay on the server; the adapter opens no database. Local presenter commands use a separate maintenance port;
 * a maintenance gate blocks workspace traffic and completion invalidates all demo identities.
 */
public final class WebServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService workers = Executors.newFixedThreadPool(12);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI upstream;
    private final String credential;
    private final Path assets;
    private final PresenterControl presenter;
    private String presenterEpoch;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Semaphore upstreamSlots = new Semaphore(2);
    private static final long SESSION_MS = Duration.ofHours(8).toMillis();
    private static final int BODY_LIMIT = 1024 * 1024, RESPONSE_LIMIT = 16 * 1024 * 1024;
    private static final Set<String> BUSINESS = Set.of("catalog", "populations", "drafts", "runs", "publications", "admissions");
    private static final Set<String> AI = Set.of("health", "defaults", "parameters", "policies", "workflows", "simulation", "feedback", "performance", "publication-previews", "publications", "notices", "conversations");
    private static final class Session {
        final String csrf = random();
        volatile String mode = "ANONYMOUS", customerId;
        volatile long touched = System.currentTimeMillis();
    }
    private record Reply(int status, byte[] body) {}
    private static final class Failure extends RuntimeException {
        final int status;
        Failure(int status, String message) { super(message); this.status = status; }
    }

    /** Starts the loopback GUI with owner-API transport and a checkout-scoped presenter coordinator. */
    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("engine.home", ".")).toAbsolutePath().normalize();
        String token = Files.readString(root.resolve("runtime/api-token")).trim();
        if (token.length() < 32) throw new IllegalStateException("Start the engine first; a valid local API token is required.");
        Path assets = root.resolve("frontend/dist");
        if (!Files.isRegularFile(assets.resolve("index.html"))) throw new IllegalStateException("Build the GUI first: scripts/build-gui.ps1");
        int port = Integer.getInteger("gui.port", 8080);
        var web = new WebServer(URI.create("http://127.0.0.1:" + Integer.getInteger("engine.api.port", 8090) + "/api/v1/"), token, assets, port, PresenterControl.local(root));
        Runtime.getRuntime().addShutdownHook(new Thread(web::close));
        web.start();
        System.out.println("Lending workspace: http://127.0.0.1:" + web.port());
        System.out.println("Local demo customer selection and Business mode; no password accounts.");
    }

    /** Wires browser transport without maintenance; tests may inject a presenter command port separately. */
    public WebServer(URI upstream, String token, Path assets, int port) throws IOException {
        this(upstream, token, assets, port, null);
    }
    WebServer(URI upstream, String token, Path assets, int port, PresenterControl presenter) throws IOException {
        this.presenter = presenter;
        this.presenterEpoch = presenter == null ? "" : presenter.epoch();
        if (!"http".equals(upstream.getScheme()) || !"127.0.0.1".equals(upstream.getHost()) || !upstream.getPath().endsWith("/api/v1/")) throw new IllegalArgumentException("Use a loopback engine API");
        this.upstream = upstream; this.credential = token; this.assets = assets.toAbsolutePath().normalize();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 32);
        server.createContext("/", this::handle); server.setExecutor(workers);
    }
    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }

    /** Checks the local browser origin before establishing a session or dispatching a command. */
    private void handle(HttpExchange x) throws IOException {
        try {
            String host = x.getRequestHeaders().getFirst("Host");
            if (!Set.of("127.0.0.1:" + port(), "localhost:" + port()).contains(host == null ? "" : host)) throw new Failure(403, "Unrecognized GUI host");
            String origin = x.getRequestHeaders().getFirst("Origin");
            if (origin != null && !origin.equals("http://" + host)) throw new Failure(403, "Use the same local GUI origin");
            if ("cross-site".equals(x.getRequestHeaders().getFirst("Sec-Fetch-Site"))) throw new Failure(403, "Cross-site requests are disabled");
            String path = x.getRequestURI().getPath();
            if (path.startsWith("/web-api/")) api(x, path.substring(9));
            else asset(x, path);
        } catch (Failure e) { json(x, e.status, Map.of("error", e.getMessage())); }
        catch (Exception e) { System.err.println("GUI request failed: " + e.getClass().getSimpleName()); json(x, 503, Map.of("error", "The local service is unavailable. Check the engine window and retry deliberately.")); }
        finally { x.close(); }
    }

    /** Selects a local identity and expires all workspace sessions after completed presenter maintenance. */
    private synchronized Session session(HttpExchange x) {
        if(presenter != null) {
            String epoch = presenter.epoch();
            if(!epoch.equals(presenterEpoch)) { sessions.clear(); presenterEpoch = epoch; }
        }
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> now - e.getValue().touched > SESSION_MS);
        String cookie = x.getRequestHeaders().getFirst("Cookie");
        String id = null;
        if (cookie != null) for (String part : cookie.split(";")) if (part.trim().startsWith("lending_gui=")) id = part.trim().substring(12);
        if (id != null && id.length() >= 2 && id.startsWith("\"") && id.endsWith("\"")) id = id.substring(1, id.length() - 1);
        Session session = id == null ? null : sessions.get(id);
        if (session == null) {
            if (sessions.size() >= 1000) throw new Failure(429, "Too many local sessions");
            id = random(); session = new Session(); sessions.put(id, session);
            x.getResponseHeaders().add("Set-Cookie", "lending_gui=" + id + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=28800");
        }
        session.touched = now;
        return session;
    }
    private static Map<String, Object> view(Session s) { return Map.of("mode", s.mode, "customerId", s.customerId == null ? "" : s.customerId, "csrfToken", s.csrf); }
    private static void requireMode(Session s, String mode) { if (!s.mode.equals(mode)) throw new Failure(403, "Open the " + mode.toLowerCase(Locale.ROOT) + " workspace first"); }

    /** Dispatches confirmed presenter commands separately and gates owner-API traffic during maintenance. */
    private void api(HttpExchange x, String path) throws Exception {
        Session s = session(x);
        String method = x.getRequestMethod();
        if (!Set.of("GET", "POST", "PUT").contains(method)) throw new Failure(405, "Method not supported");
        if (!method.equals("GET")) {
            String csrf = x.getRequestHeaders().getFirst("X-CSRF-Token");
            if (csrf == null || !MessageDigest.isEqual(csrf.getBytes(StandardCharsets.UTF_8), s.csrf.getBytes(StandardCharsets.UTF_8))) throw new Failure(403, "Refresh the page before submitting");
            if (!"application/json".equalsIgnoreCase(Optional.ofNullable(x.getRequestHeaders().getFirst("Content-Type")).orElse("").split(";")[0].trim())) throw new Failure(415, "JSON required");
        }
        byte[] body = method.equals("GET") ? new byte[0] : x.getRequestBody().readNBytes(BODY_LIMIT + 1);
        if (body.length > BODY_LIMIT) throw new Failure(413, "Request too large");
        if (path.equals("session") && method.equals("GET")) { json(x, 200, view(s)); return; }
        if(path.startsWith("presenter/")) {
            if(presenter == null) throw new Failure(503, "Presenter controls require the standard local GUI launcher.");
            JsonNode result;
            if(path.equals("presenter/status") && method.equals("GET")) result=presenter.invoke("status");
            else if(path.equals("presenter/reset") && method.equals("POST")) {
                JsonNode command=bodyJson(body);
                var fields=new HashSet<String>();command.fieldNames().forEachRemaining(fields::add);
                String action=command.path("action").asText(), id=command.path("requestId").asText();
                if(!fields.equals(Set.of("action","requestId","confirmed")) || !command.path("confirmed").isBoolean() ||
                    !command.path("confirmed").booleanValue() || !Set.of("CLEAR","A2A").contains(action) ||
                    !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                    throw new Failure(422, "Confirm one presenter action with a UUID request ID.");
                result=presenter.invoke("start",action,id);
            } else throw new Failure(404,"Presenter operation not found");
            json(x,result.path("httpStatus").asInt(200),result);return;
        }
        if(presenter != null && presenter.maintenance()) throw new Failure(503,"Presenter maintenance is running. Wait for the prepared landing page.");
        if (path.equals("session/business") && method.equals("POST")) { s.customerId = null; s.mode = "BUSINESS"; json(x, 200, view(s)); return; }
        if (path.equals("session/logout") && method.equals("POST")) { s.customerId = null; s.mode = "ANONYMOUS"; json(x, 200, view(s)); return; }
        if (path.equals("session/customer") && method.equals("POST")) {
            String id = bodyJson(body).path("customerId").asText();
            identifier(id);
            Reply reply = request("GET", "customers/" + id, new byte[0], null);
            if (reply.status() >= 400) { relay(x, reply); return; }
            s.customerId = id; s.mode = "CUSTOMER"; json(x, 200, view(s)); return;
        }
        if (path.equals("health") && method.equals("GET")) { relay(x, request("GET", "health", body, null)); return; }
        if (path.equals("customers") && method.equals("GET")) { relay(x, request(method, query(x, "customers"), body, null)); return; }
        if (path.equals("customers") && method.equals("POST")) {
            if (!s.mode.equals("ANONYMOUS")) throw new Failure(409, "Sign out before creating another customer");
            Reply reply = request(method, "customers", body, null);
            if (reply.status() < 400) { s.customerId = Json.MAPPER.readTree(reply.body()).path("id").asText(); s.mode = "CUSTOMER"; }
            relay(x, reply); return;
        }
        String destination;
        if (path.equals("customer") || path.startsWith("customer/")) {
            requireMode(s, "CUSTOMER");
            String tail = path.substring("customer".length());
            if (tail.isEmpty()) { if (!Set.of("GET", "PUT").contains(method)) throw new Failure(405, "Read or update this customer"); destination = "customers/" + s.customerId; }
            else if (tail.matches("/personalization(/[A-Za-z0-9_.:-]+(/(cancel|messages))?)?")) {
                if(!Set.of("GET","POST").contains(method))throw new Failure(405,"Personalization supports reads and explicit customer commands");
                destination = "ai/customer-personalization/" + s.customerId + tail.substring("/personalization".length());
            }
            else if(method.equals("GET") && tail.matches("/offer-review/[A-Za-z0-9_.:-]+")) destination="offer-creation/customers/"+s.customerId+"/offers/"+tail.substring("/offer-review/".length());
            else if (method.equals("GET") && tail.matches("/offers(/[A-Za-z0-9_.:-]+)?|/campaign-packages(/[A-Za-z0-9_.:-]+/files)?")) destination = "customers/" + s.customerId + tail;
            else if (method.equals("POST") && tail.matches("/offers/[A-Za-z0-9_.:-]+/selections")) destination = "offer-creation/customers/" + s.customerId + tail;
            else throw new Failure(404, "Customer operation not found");
        } else if (path.startsWith("business/") || path.startsWith("ai/")) {
            requireMode(s, "BUSINESS");
            String[] parts = path.split("/");
            if (parts.length < 2 || !(parts[0].equals("business") ? BUSINESS : AI).contains(parts[1])) throw new Failure(404, "Workspace operation not found");
            for (String part : parts) identifier(part);
            destination = path;
        } else throw new Failure(404, "GUI operation not found");
        relay(x, request(method, query(x, destination), body, x.getRequestHeaders().getFirst("Idempotency-Key")));
    }
    private static JsonNode bodyJson(byte[] bytes) { try { JsonNode node = Json.MAPPER.readTree(bytes); if (node == null || !node.isObject()) throw new Failure(422, "Expected a JSON object"); return node; } catch (Exception e) { throw new Failure(422, "Invalid JSON"); } }
    private static void identifier(String value) { if (value == null || !value.matches("[A-Za-z0-9_:-][A-Za-z0-9_.:-]{0,159}") || value.contains("..")) throw new Failure(422, "Invalid resource identifier"); }
    private static String query(HttpExchange x, String path) { String q = x.getRequestURI().getRawQuery(); return path + (q == null ? "" : "?" + q); }

    /** Preserves owner responses while bounding traffic; queued requests recheck the maintenance gate. */
    private Reply request(String method, String path, byte[] body, String key) throws Exception {
        if (!upstreamSlots.tryAcquire(125, TimeUnit.SECONDS)) throw new Failure(503, "Engine busy; retry shortly");
        try {
            if(presenter != null && presenter.maintenance()) throw new Failure(503,"Presenter maintenance is running.");
            var b = HttpRequest.newBuilder(upstream.resolve(path)).timeout(Duration.ofSeconds(130)).header("Authorization", "Bearer " + credential).header("Content-Type", "application/json");
            if (key != null) { if (!key.matches("[A-Za-z0-9_.:-]{1,160}")) throw new Failure(422, "Invalid request key"); b.header("Idempotency-Key", key); }
            var response = client.send(b.method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                byte[] bytes = in.readNBytes(RESPONSE_LIMIT + 1);
                if (bytes.length > RESPONSE_LIMIT) throw new Failure(503, "Response too large; use a smaller page");
                if (response.statusCode() >= 300 && response.statusCode() < 400) throw new Failure(502, "Unexpected upstream redirect");
                return new Reply(response.statusCode(), bytes);
            }
        } finally { upstreamSlots.release(); }
    }

    /** Serves compiled frontend files and the root entry point; unknown paths never expose project files. */
    private void asset(HttpExchange x, String path) throws IOException {
        if (!x.getRequestMethod().equals("GET")) throw new Failure(405, "Method not supported");
        Path file = assets.resolve(path.substring(1)).normalize();
        if (!file.startsWith(assets) || path.contains("\\") || path.contains("..")) throw new Failure(404, "File not found");
        if (!Files.isRegularFile(file)) {
            if (!path.equals("/")) throw new Failure(404, "File not found");
            file = assets.resolve("index.html");
        }
        if (!Files.isRegularFile(file) || !file.toRealPath().startsWith(assets.toRealPath())) throw new Failure(503, "GUI assets unavailable; run scripts/build-gui.ps1");
        String name = file.getFileName().toString();
        String type = name.endsWith(".js") ? "text/javascript" : name.endsWith(".css") ? "text/css" : name.endsWith(".svg") ? "image/svg+xml" : name.endsWith(".png") ? "image/png" : name.endsWith(".ico") ? "image/x-icon" : "text/html";
        send(x, 200, Files.readAllBytes(file), type + "; charset=utf-8");
    }
    private static void relay(HttpExchange x, Reply r) throws IOException { send(x, r.status(), r.body(), "application/json; charset=utf-8"); }
    private static void json(HttpExchange x, int status, Object value) throws IOException { send(x, status, Json.write(value).getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8"); }
    private static void send(HttpExchange x, int status, byte[] body, String type) throws IOException {
        x.getResponseHeaders().set("Content-Type", type);
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        x.getResponseHeaders().set("Referrer-Policy", "same-origin");
        x.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
        x.sendResponseHeaders(status, body.length); x.getResponseBody().write(body);
    }
    private static String random() { byte[] b = new byte[32]; new SecureRandom().nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    public void close() { server.stop(0); workers.shutdownNow(); }
}

