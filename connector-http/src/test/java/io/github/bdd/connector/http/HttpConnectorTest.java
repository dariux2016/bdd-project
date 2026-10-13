package io.github.bdd.connector.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.bdd.engine.Engine;
import io.github.bdd.engine.ExecutionRequest;
import io.github.bdd.engine.ExecutionResult;
import io.github.bdd.engine.ScenarioResult;
import io.github.bdd.engine.Status;
import io.github.bdd.engine.StepResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The HTTP connector, exercised through the engine against a test server that echoes what it receives. */
class HttpConnectorTest {

    @TempDir
    Path workspace;

    private HttpServer server;
    private final AtomicInteger inventoryCalls = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", this::echo);
        server.createContext("/inventory", exchange -> {
            // The stock only shows after the third call, as the effect of an asynchronous flow.
            if (inventoryCalls.incrementAndGet() < 3) {
                reply(exchange, 404, "{\"error\": \"not yet\"}");
            } else {
                reply(exchange, 200, "{\"stock\": 7}");
            }
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            reply(exchange, 200, "{}");
        });
        server.createContext("/text", exchange -> reply(exchange, 200, "plain words", "text/plain"));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** Answers with the request it received, as JSON. */
    private void echo(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String headers = exchange.getRequestHeaders().entrySet().stream()
                .map(entry -> "\"" + entry.getKey().toLowerCase() + "\": \"" + entry.getValue().getFirst() + "\"")
                .reduce((a, b) -> a + ", " + b).orElse("");
        String query = exchange.getRequestURI().getRawQuery();
        reply(exchange, 201, """
                {"method": "%s", "path": "%s", "query": "%s", "body": %s, "headers": {%s}, "id": 42}"""
                .formatted(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        query == null ? "" : query, body.isBlank() ? "null" : body, headers));
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        reply(exchange, status, body, "application/json");
    }

    private static void reply(HttpExchange exchange, int status, String body, String type) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.getResponseHeaders().add("X-Served-By", "test");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void requestIsSentWithTheMethodThePathAndTheQueryString() throws IOException {
        StepResult last = run("""
                When I send a GET request to "/echo?color=red" on the HTTP service "api"
                Then the last response of the HTTP service "api" satisfies the conditions
                  | field        | operator | value     |
                  | status       | equals   | 201       |
                  | body.method  | equals   | GET       |
                  | body.path    | equals   | /echo     |
                  | body.query   | equals   | color=red |
                """);
        assertThat(last.status()).isEqualTo(Status.PASSED);
    }

    @Test
    void headersGoInATableAndTheBodyInADocString() throws IOException {
        StepResult last = run("""
                When I send a PUT request to "/echo" on the HTTP service "api"
                  | name    | value   |
                  | X-Trace | abc-123 |
                Then the body of the last response of the HTTP service "api" contains the JSON
                  \"\"\"
                  {"method": "PUT", "headers": {"x-trace": "abc-123"}}
                  \"\"\"
                """);
        assertThat(last.status()).isEqualTo(Status.PASSED);
    }

    @Test
    void bodyIsSentAsJsonAndTheResponseIsCompared() throws IOException {
        StepResult last = run("""
                When I send a POST request to "/echo" on the HTTP service "api"
                  \"\"\"
                  {"name": "Ada", "age": 36}
                  \"\"\"
                Then the body of the last response of the HTTP service "api" equals the JSON ignoring "$.headers"
                  \"\"\"
                  {"method": "POST", "path": "/echo", "query": "", "id": 42,
                   "body": {"name": "Ada", "age": 36}}
                  \"\"\"
                And the last response of the HTTP service "api" satisfies the conditions
                  | field                | operator | value            |
                  | headers.content-type | equals   | application/json |
                  | headers.x-served-by  | equals   | test             |
                """);
        assertThat(last.status()).isEqualTo(Status.PASSED);
    }

    @Test
    void headerAndQueryParameterOfTheNextRequestAreSetByTheirOwnSteps() throws IOException {
        StepResult last = run("""
                Given the next request to the HTTP service "api" has the header "X-Tenant" set to "acme"
                And the next request to the HTTP service "api" has the query parameter "q" set to "a b&c"
                When I send a POST request to "/echo" on the HTTP service "api"
                  \"\"\"
                  {"n": 1}
                  \"\"\"
                Then the last response of the HTTP service "api" satisfies the conditions
                  | field                 | operator | value     |
                  | body.query            | equals   | q=a+b%26c |
                  | body.headers.x-tenant | equals   | acme      |
                  | body.body.n           | equals   | 1         |
                """);
        assertThat(last.status()).isEqualTo(Status.PASSED);
    }

    @Test
    void headersOfTheNextRequestApplyToOneRequestOnly() throws IOException {
        StepResult last = run("""
                Given the next request to the HTTP service "api" has the header "X-Once" set to "yes"
                When I send a GET request to "/echo" on the HTTP service "api"
                And I send a GET request to "/echo" on the HTTP service "api"
                Then the last response of the HTTP service "api" satisfies the conditions
                  | field               | operator | value |
                  | body.headers.x-once | is null  |       |
                """);
        assertThat(last.status()).isEqualTo(Status.PASSED);
    }

    @Test
    void sourceConfigurationGivesTheBaseAddressAndDefaultHeadersWithSecretsFromTheEnvironment()
            throws IOException {
        StepResult last = run("""
                When I send a GET request to "/echo" on the HTTP service "secured"
                Then the last response of the HTTP service "secured" satisfies the conditions
                  | field                      | operator | value         |
                  | body.headers.authorization | equals   | Bearer s3cret |
                  | body.headers.x-client      | equals   | bdd           |
                """, Map.of("API_TOKEN", "s3cret"));
        assertThat(last.status()).isEqualTo(Status.PASSED);
    }

    @Test
    void headerOfTheStepOverridesTheDefaultOne() throws IOException {
        StepResult last = run("""
                Given the next request to the HTTP service "secured" has the header "x-client" set to "other"
                When I send a GET request to "/echo" on the HTTP service "secured"
                Then the last response of the HTTP service "secured" satisfies the conditions
                  | field                 | operator | value |
                  | body.headers.x-client | equals   | other |
                """, Map.of("API_TOKEN", "s3cret"));
        assertThat(last.status()).isEqualTo(Status.PASSED);
    }

    @Test
    void fieldOfTheResponseIsSavedInAVariableForTheFollowingSteps() throws IOException {
        StepResult last = run("""
                When I send a POST request to "/echo" on the HTTP service "api"
                And I save the "body.id" of the last response of the HTTP service "api" as "orderId"
                And I send a GET request to "/echo/${orderId}" on the HTTP service "api"
                Then the last response of the HTTP service "api" satisfies the conditions
                  | field     | operator | value    |
                  | body.path | equals   | /echo/42 |
                """);
        assertThat(last.status()).isEqualTo(Status.PASSED);
    }

    @Test
    void callIsRepeatedUntilTheResponseSatisfiesTheCondition() throws IOException {
        StepResult last = run("""
                When I call GET "/inventory" on the HTTP service "api" until the response satisfies the conditions
                  | field      | operator | value |
                  | status     | equals   | 200   |
                  | body.stock | equals   | 7     |
                """, Map.of(), "5000ms");
        assertThat(last.status()).isEqualTo(Status.PASSED);
        assertThat(inventoryCalls).hasValue(3);
    }

    @Test
    void repeatedCallFailsShowingTheLastResponseWhenTheTimeoutExpires() throws IOException {
        StepResult last = run("""
                When I call GET "/inventory" on the HTTP service "api" until the response satisfies the conditions within 0 seconds
                  | field  | operator | value |
                  | status | equals   | 200   |
                """);
        assertThat(last.status()).isEqualTo(Status.FAILED);
        assertThat(last.error()).startsWith("Expected a matching record, but 0 of the 1 observed records match")
                .contains("not yet");
    }

    @Test
    void methodsThatModifyDataAreBlockedOnAReadOnlySource() throws IOException {
        assertThat(run("""
                When I send a GET request to "/echo" on the HTTP service "mirror"
                """).status()).isEqualTo(Status.PASSED);
        for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
            StepResult last = run("When I send a " + method + " request to \"/echo\" on the HTTP service \"mirror\"\n");
            assertThat(last.status()).as(method).isEqualTo(Status.FAILED);
            assertThat(last.error()).as(method).contains("read-only");
        }
    }

    @Test
    void responseNotReceivedInTimeFailsClearly() throws IOException {
        StepResult last = run("""
                When I send a GET request to "/slow" on the HTTP service "impatient"
                """);
        assertThat(last.status()).isEqualTo(Status.FAILED);
        assertThat(last.error()).contains("No response from http://127.0.0.1:" + server.getAddress().getPort()
                + "/slow within 1 second(s)").contains("responseTimeout");
    }

    @Test
    void unreachableServiceFailsClearly() throws IOException {
        int port = server.getAddress().getPort();
        server.stop(0);
        StepResult last = run("""
                When I send a GET request to "/echo" on the HTTP service "api"
                """);
        assertThat(last.status()).isEqualTo(Status.FAILED);
        assertThat(last.error()).contains("Could not connect to http://127.0.0.1:" + port + "/echo");
    }

    @Test
    void responseThatIsNotJsonHasItsTextAsBody() throws IOException {
        StepResult last = run("""
                When I send a GET request to "/text" on the HTTP service "api"
                Then the last response of the HTTP service "api" satisfies the conditions
                  | field | operator | value |
                  | body  | contains | words |
                """);
        assertThat(last.status()).isEqualTo(Status.PASSED);
    }

    @Test
    void checkingTheResponseBeforeAnyRequestFailsClearly() throws IOException {
        StepResult last = run("""
                Then the last response of the HTTP service "api" satisfies the conditions
                """);
        assertThat(last.status()).isEqualTo(Status.FAILED);
        assertThat(last.error()).contains("No request was sent to the HTTP service 'api' yet");
    }

    @Test
    void phrasesWorkInItalian() throws IOException {
        configure("0ms");
        Path file = workspace.resolve("features/api.feature");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                # language: it
                Funzionalità: API
                  Scenario: Ordine
                    Quando invio una richiesta POST a "/echo" sul servizio HTTP "api"
                      \"\"\"
                      {"n": 1}
                      \"\"\"
                    E salvo "body.id" dell'ultima risposta del servizio HTTP "api" come "numero"
                    Allora l'ultima risposta del servizio HTTP "api" soddisfa le condizioni
                      | campo  | operatore | valore |
                      | status | uguale    | 201    |
                      | body.id | uguale   | 42     |
                    E il corpo dell'ultima risposta del servizio HTTP "api" contiene il JSON
                      \"\"\"
                      {"body": {"n": 1}}
                      \"\"\"
                    E chiamo GET "/echo/${numero}" sul servizio HTTP "api" finché la risposta soddisfa le condizioni
                      | campo     | operatore | valore   |
                      | body.path | uguale    | /echo/42 |
                """);
        ExecutionResult result = Engine.run(new ExecutionRequest(workspace, "local", withToken(Map.of())));
        ScenarioResult scenario = result.scenarios().getFirst();
        assertThat(scenario.steps()).extracting(StepResult::status).containsOnly(Status.PASSED);
    }

    private StepResult run(String steps) throws IOException {
        return run(steps, Map.of());
    }

    private StepResult run(String steps, Map<String, String> environment) throws IOException {
        return run(steps, environment, "0ms");
    }

    /** Runs a scenario made of the given steps; returns its last step. */
    private StepResult run(String steps, Map<String, String> environment, String timeout) throws IOException {
        configure(timeout);
        Path file = workspace.resolve("features/api.feature");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "Feature: API\n  Scenario: Calls\n" + steps.indent(4));
        ExecutionResult result = Engine.run(new ExecutionRequest(workspace, "local", withToken(environment)));
        return result.scenarios().getFirst().steps().getLast();
    }

    /** The secured source needs its token even in the runs that do not use it. */
    private static Map<String, String> withToken(Map<String, String> environment) {
        Map<String, String> all = new java.util.HashMap<>(Map.of("API_TOKEN", "unused"));
        all.putAll(environment);
        return all;
    }

    private void configure(String timeout) throws IOException {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        Files.writeString(workspace.resolve("sources.yaml"), """
                profiles:
                  local:
                    sources:
                      api:
                        type: http
                        config:
                          baseUrl: %1$s
                      secured:
                        type: http
                        config:
                          baseUrl: %1$s/
                          authorization: Bearer ${API_TOKEN}
                          headers: |
                            X-Client: bdd
                      mirror:
                        type: http
                        readOnly: true
                        config:
                          baseUrl: %1$s
                      impatient:
                        type: http
                        config:
                          baseUrl: %1$s
                          responseTimeout: 1
                """.formatted(base));
        Files.writeString(workspace.resolve("settings.yaml"), """
                verification:
                  timeout: %s
                  pollInterval: 100ms
                """.formatted(timeout));
    }
}
