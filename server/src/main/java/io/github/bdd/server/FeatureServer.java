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
import java.util.LinkedHashMap;
import java.util.Map;

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
 * </pre>
 */
public final class FeatureServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer http;
    private final FeatureStore store;

    public FeatureServer(Path workspace, int port) throws IOException {
        this.store = new FeatureStore(workspace.resolve("features"));
        this.http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
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
        http.stop(0);
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
                default -> error(exchange, 404, "Unknown endpoint: " + route);
            }
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
