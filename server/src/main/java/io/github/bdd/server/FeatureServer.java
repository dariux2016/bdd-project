package io.github.bdd.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP API over the features directory of a workspace, plus the static UI from the same artifact.
 * Binds to the loopback interface only.
 *
 * <pre>
 * GET    /api/tree
 * GET    /api/file?path=a/b.feature                       -> {path, content, version}
 * PUT    /api/file?path=...  {content, version, overwrite?}  409 if the file changed on disk
 * POST   /api/file?path=...  {content?}                   create
 * POST   /api/folder?path=...                             create
 * POST   /api/rename         {from, to}
 * DELETE /api/entry?path=...
 *
 * POST   /api/runs           {profile?, paths?, tags?}    start an execution; 409 if one is running
 * GET    /api/runs/current                                {run: {...} | null}: the current or last execution
 * GET    /api/runs/events                                 Server-Sent Events of the current or last
 *                                                         execution, from its start (or Last-Event-ID)
 * POST   /api/runs/cancel                                 stop the running execution
 * </pre>
 */
public final class FeatureServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer http;
    private final FeatureStore store;
    private final Executions executions;
    /** An event stream holds its thread for as long as the execution runs. */
    private final ExecutorService threads = Executors.newCachedThreadPool();

    public FeatureServer(Path workspace, int port) throws IOException {
        this.store = new FeatureStore(workspace.resolve("features"));
        this.executions = new Executions(workspace, System.getenv());
        this.http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        http.setExecutor(threads);
        http.createContext("/api/", this::api);
        http.createContext("/", this::staticResource);
    }

    public FeatureServer start() {
        http.start();
        return this;
    }

    public int port() {
        return http.getAddress().getPort();
    }

    @Override
    public void close() {
        executions.shutdown();
        http.stop(0);
        threads.shutdownNow();
    }

    private void api(HttpExchange exchange) throws IOException {
        try {
            String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            switch (route) {
                case "GET /api/tree" -> send(exchange, 200, store.tree());
                case "GET /api/file" -> send(exchange, 200, store.read(param(exchange, "path")));
                case "PUT /api/file" -> {
                    JsonNode body = body(exchange);
                    send(exchange, 200, store.write(param(exchange, "path"), requireText(body, "content"),
                            text(body, "version"), body.path("overwrite").asBoolean(false)));
                }
                case "POST /api/file" -> {
                    String content = text(body(exchange), "content");
                    send(exchange, 201, store.createFile(param(exchange, "path"), content == null ? "" : content));
                }
                case "POST /api/folder" -> {
                    store.createFolder(param(exchange, "path"));
                    send(exchange, 201, Map.of("path", param(exchange, "path")));
                }
                case "POST /api/rename" -> {
                    JsonNode body = body(exchange);
                    store.rename(requireText(body, "from"), requireText(body, "to"));
                    send(exchange, 200, Map.of("path", requireText(body, "to")));
                }
                case "DELETE /api/entry" -> {
                    store.delete(param(exchange, "path"));
                    send(exchange, 200, Map.of("deleted", param(exchange, "path")));
                }
                case "POST /api/runs" -> {
                    JsonNode body = body(exchange);
                    List<String> paths = new ArrayList<>();
                    body.path("paths").forEach(path -> paths.add(path.asText()));
                    send(exchange, 202, executions.start(text(body, "profile"), paths, text(body, "tags")));
                }
                case "GET /api/runs/current" -> {
                    Executions.Run run = executions.latest();
                    Map<String, Object> reply = new LinkedHashMap<>();
                    reply.put("run", run == null ? null : run.summary());
                    send(exchange, 200, reply);
                }
                case "GET /api/runs/events" -> events(exchange);
                case "POST /api/runs/cancel" -> {
                    if (!executions.cancel()) {
                        error(exchange, 409, "No execution is running");
                    } else {
                        send(exchange, 202, Map.of("cancelling", true));
                    }
                }
                default -> error(exchange, 404, "Unknown endpoint: " + route);
            }
        } catch (Executions.BusyException e) {
            error(exchange, 409, e.getMessage());
        } catch (Executions.StartException e) {
            error(exchange, 400, e.getMessage());
        } catch (FeatureStore.PathRejectedException e) {
            error(exchange, 400, e.getMessage());
        } catch (FeatureStore.NotFoundException e) {
            error(exchange, 404, e.getMessage());
        } catch (FeatureStore.ConflictException e) {
            error(exchange, 409, e.getMessage());
        } catch (IllegalArgumentException e) {
            error(exchange, 400, e.getMessage());
        } catch (Exception e) {
            error(exchange, 500, String.valueOf(e.getMessage()));
        } finally {
            exchange.close();
        }
    }

    /**
     * Streams the events of the latest execution from its start, or from after the id the client
     * last saw, and ends the stream when the execution has ended and everything was sent.
     */
    private void events(HttpExchange exchange) throws IOException {
        Executions.Run run = executions.latest();
        if (run == null) {
            error(exchange, 404, "No execution has run yet");
            return;
        }
        int seen = 0;
        String last = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        if (last != null) {
            try {
                seen = Math.max(0, Integer.parseInt(last.strip()));
            } catch (NumberFormatException ignored) {
                // Streams from the start.
            }
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, 0);
        var out = exchange.getResponseBody();
        try {
            while (true) {
                boolean news = run.awaitEventsAfter(seen, 10_000);
                Executions.Snapshot snapshot = run.snapshot();
                if (!news) {
                    out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    continue;
                }
                for (Executions.Event event : snapshot.events()) {
                    if (event.id() > seen) {
                        String frame = "id: " + event.id() + "\nevent: " + event.name() + "\ndata: "
                                + JSON.writeValueAsString(event.data()) + "\n\n";
                        out.write(frame.getBytes(StandardCharsets.UTF_8));
                        seen = event.id();
                    }
                }
                out.flush();
                if (snapshot.finished() && seen >= snapshot.events().size()) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // The client went away.
        }
    }

    private void staticResource(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (!exchange.getRequestMethod().equals("GET")) {
                error(exchange, 405, "Method not allowed");
                return;
            }
            if (path.equals("/")) {
                path = "/index.html";
            }
            if (path.contains("..") || path.contains("\\")) {
                error(exchange, 404, "Not found");
                return;
            }
            try (InputStream in = FeatureServer.class.getResourceAsStream("/static" + path)) {
                if (in == null) {
                    error(exchange, 404, "Not found");
                    return;
                }
                byte[] bytes = in.readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", contentType(path));
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        } finally {
            exchange.close();
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        return "application/octet-stream";
    }

    private static String param(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                if (key.equals(name)) {
                    return eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalArgumentException("Missing parameter: " + name);
    }

    private static JsonNode body(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(bytes);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON body");
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String requireText(JsonNode body, String field) {
        String value = text(body, field);
        if (value == null) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        return value;
    }

    private static void error(HttpExchange exchange, int status, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        send(exchange, status, body);
    }

    private static void send(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(payload);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
