package io.github.bdd.connector.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** An HTTP service on a free local port, standing in for the system under test. */
final class TestService implements AutoCloseable {

    /**
     * A request the service received.
     *
     * @param target the path and query string, as sent
     * @param headers by lowercase name
     */
    record Request(String method, String target, Map<String, String> headers, String body) {

        String path() {
            int query = target.indexOf('?');
            return query < 0 ? target : target.substring(0, query);
        }
    }

    /** What the service answers to a request. */
    record Reply(int status, Map<String, String> headers, String body, long delayMillis) {

        static Reply json(int status, String body) {
            return new Reply(status, Map.of("Content-Type", "application/json"), body, 0);
        }

        static Reply status(int status) {
            return new Reply(status, Map.of(), "", 0);
        }

        Reply withHeader(String name, String value) {
            Map<String, String> all = new TreeMap<>(headers);
            all.put(name, value);
            return new Reply(status, all, body, delayMillis);
        }

        Reply after(long millis) {
            return new Reply(status, headers, body, millis);
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Request> received = new CopyOnWriteArrayList<>();

    private TestService(Function<Request, Reply> replies) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> handle(exchange, replies));
        server.setExecutor(executor);
        server.start();
    }

    static TestService start(Function<Request, Reply> replies) throws IOException {
        return new TestService(replies);
    }

    /** The base URL of the service, e.g. {@code http://localhost:4321}. */
    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    List<Request> received() {
        return List.copyOf(received);
    }

    private void handle(HttpExchange exchange, Function<Request, Reply> replies) throws IOException {
        try (exchange) {
            Map<String, String> headers = new TreeMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name.toLowerCase(Locale.ROOT), String.join(", ", values)));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery()),
                    headers, body);
            received.add(request);
            Reply reply = replies.apply(request);
            if (reply.delayMillis() > 0) {
                try {
                    Thread.sleep(reply.delayMillis());
                } catch (InterruptedException e) {
                    return;
                }
            }
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            boolean empty = bytes.length == 0 || request.method().equals("HEAD");
            exchange.sendResponseHeaders(reply.status(), empty ? -1 : bytes.length);
            if (!empty) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        } catch (IOException e) {
            // The client gave up, as when it times out.
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    /** Requests of a method, in the order received. */
    List<Request> received(String method) {
        List<Request> matching = new ArrayList<>();
        for (Request request : received) {
            if (request.method().equals(method)) {
                matching.add(request);
            }
        }
        return matching;
    }
}
