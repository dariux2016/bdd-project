package io.github.bdd.connector.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bdd.connector.http.TestService.Reply;
import io.github.bdd.connector.http.TestService.Request;
import io.github.bdd.engine.Engine;
import io.github.bdd.engine.ExecutionRequest;
import io.github.bdd.engine.ExecutionResult;
import io.github.bdd.engine.ScenarioResult;
import io.github.bdd.engine.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Runs the example workspace, whose features are written in English and in Italian, against an orders API. */
class ExampleFeaturesTest {

    private static final String TOKEN = "example-token";

    @TempDir
    Path workspace;

    @Test
    void exampleFeaturesPassAgainstTheOrdersApi() throws Exception {
        copyExamples();
        OrdersApi api = new OrdersApi();
        try (TestService service = TestService.start(api::reply)) {
            ExecutionResult result = Engine.run(new ExecutionRequest(workspace, "local",
                    Map.of("ORDERS_URL", service.url(), "ORDERS_TOKEN", TOKEN)));

            assertThat(result.scenarios())
                    .extracting(ScenarioResult::feature, ScenarioResult::status)
                    .containsOnly(
                            tuple("features/orders.feature", Status.PASSED),
                            tuple("features/ordini.feature", Status.PASSED));
            assertThat(result.scenarios()).hasSize(5);
            assertThat(service.received()).allSatisfy(request ->
                    assertThat(request.headers()).containsEntry("authorization", "Bearer " + TOKEN));
        }
    }

    private void copyExamples() throws IOException, URISyntaxException {
        Path examples = Path.of(getClass().getResource("/examples").toURI());
        try (Stream<Path> files = Files.walk(examples)) {
            files.forEach(file -> {
                try {
                    Path target = workspace.resolve(examples.relativize(file).toString());
                    if (Files.isDirectory(file)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(file, target);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    /** An orders service whose orders are paid a moment after they are created, as by an asynchronous process. */
    private static final class OrdersApi {

        private static final Pattern ORDER = Pattern.compile("/api/orders/(\\d+)");
        private static final long PAID_AFTER_MILLIS = 300;

        private final ObjectMapper json = new ObjectMapper();
        private final AtomicInteger ids = new AtomicInteger();
        private final Map<Integer, Map<String, Object>> orders = new ConcurrentHashMap<>();
        private final Map<Integer, Long> createdAt = new ConcurrentHashMap<>();

        Reply reply(Request request) {
            try {
                if (!("Bearer " + TOKEN).equals(request.headers().get("authorization"))) {
                    return Reply.status(401);
                }
                Matcher order = ORDER.matcher(request.path());
                if (request.path().equals("/api/orders") && request.method().equals("POST")) {
                    return create(request);
                }
                if (request.path().equals("/api/orders") && request.method().equals("GET")) {
                    String query = request.target().substring(request.target().indexOf('?') + 1);
                    return Reply.json(200, json.writeValueAsString(Map.of("query", query, "orders", List.of())));
                }
                if (order.matches() && orders.containsKey(Integer.valueOf(order.group(1)))) {
                    int id = Integer.parseInt(order.group(1));
                    return switch (request.method()) {
                        case "GET" -> Reply.json(200, json.writeValueAsString(current(id)));
                        case "DELETE" -> {
                            orders.remove(id);
                            yield Reply.status(204);
                        }
                        default -> Reply.status(405);
                    };
                }
                return Reply.json(404, "{\"error\": \"not found\"}");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private Reply create(Request request) throws IOException {
            if (request.headers().get("x-correlation-id") == null) {
                return Reply.json(400, "{\"error\": \"X-Correlation-Id is required\"}");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> order = new LinkedHashMap<>(json.readValue(request.body(), Map.class));
            int id = ids.incrementAndGet();
            order.put("id", id);
            order.put("status", "NEW");
            order.put("createdAt", java.time.Instant.now().toString());
            orders.put(id, order);
            createdAt.put(id, System.nanoTime());
            return Reply.json(201, json.writeValueAsString(order)).withHeader("Location", "/api/orders/" + id);
        }

        private Map<String, Object> current(int id) {
            Map<String, Object> order = new LinkedHashMap<>(orders.get(id));
            if (System.nanoTime() - createdAt.get(id) >= PAID_AFTER_MILLIS * 1_000_000) {
                order.put("status", "PAID");
            }
            return order;
        }
    }
}
