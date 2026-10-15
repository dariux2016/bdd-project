package io.github.bdd.connector.http;

import io.github.bdd.connector.http.TestService.Reply;
import io.github.bdd.connector.http.TestService.Request;
import io.github.bdd.engine.ConfigurationException;
import io.github.bdd.engine.Engine;
import io.github.bdd.engine.ExecutionRequest;
import io.github.bdd.engine.ExecutionResult;
import io.github.bdd.engine.Status;
import io.github.bdd.engine.StepResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpConnectorTest {

    private static final String ORDER = """
            {"id": 42, "status": "NEW", "lines": [{"sku": "A1"}], "createdAt": "2026-10-15T08:00:00Z"}""";

    @TempDir
    Path workspace;

    private TestService service;
    private Map<String, String> environment = Map.of();
    private Duration elapsed;

    @AfterEach
    void stop() {
        if (service != null) {
            service.close();
        }
    }

    // Request building

    @Test
    void requestGoesToThePathUnderTheBaseUrlOfTheSource() throws IOException {
        serve(request -> Reply.status(200));
        sources("url: " + service.url() + "/api/");

        assertThat(run("When I send a GET request to \"/orders/42\" on the HTTP service \"shop\"\n").successful())
                .isTrue();
        assertThat(service.received()).extracting(Request::method, Request::target)
                .containsExactly(tuple("GET", "/api/orders/42"));
    }

    @Test
    void queryStringIsSentAsWrittenWithIllegalCharactersEncoded() throws IOException {
        serve(request -> Reply.status(200));
        sources("url: " + service.url());

        run("When I send a GET request to \"/orders?status=NEW&customer=Ada Lovelace&q=a%2Bb\" on the HTTP "
                + "service \"shop\"\n");

        assertThat(service.received().getFirst().target())
                .isEqualTo("/orders?status=NEW&customer=Ada%20Lovelace&q=a%2Bb");
    }

    @Test
    void docStringIsTheBodyOfTheRequestAndJsonIsDeclaredAsSuch() throws IOException {
        serve(request -> Reply.status(201));
        sources("url: " + service.url());

        assertThat(run("""
                When I send a POST request to "/orders" on the HTTP service "shop"
                  ""\"
                  {"sku": "${uuid:sku}"}
                  ""\"
                And I send a PUT request to "/notes/1" on the HTTP service "shop"
                  ""\"
                  plain text
                  ""\"
                """).successful()).isTrue();

        Request post = service.received("POST").getFirst();
        assertThat(post.body()).matches("\\{\"sku\": \"[0-9a-f-]{36}\"}");
        assertThat(post.headers()).containsEntry("content-type", "application/json");
        assertThat(service.received("PUT").getFirst().headers())
                .containsEntry("content-type", "text/plain; charset=UTF-8");
    }

    @Test
    void sourceGivesDefaultHeadersIncludingSecretsFromTheEnvironment() throws IOException {
        serve(request -> Reply.status(200));
        environment = Map.of("SHOP_TOKEN", "s3cr3t");
        sources("""
                url: %s
                headers:
                  Authorization: Bearer ${SHOP_TOKEN}
                  X-Client: bdd
                """.formatted(service.url()));

        run("When I send a GET request to \"/orders\" on the HTTP service \"shop\"\n");

        assertThat(service.received().getFirst().headers())
                .containsEntry("authorization", "Bearer s3cr3t")
                .containsEntry("x-client", "bdd");
    }

    @Test
    void sourceCanAuthenticateWithUserAndPassword() throws IOException {
        serve(request -> Reply.status(200));
        environment = Map.of("SHOP_PASSWORD", "pa55");
        sources("""
                url: %s
                username: ada
                password: ${SHOP_PASSWORD}
                """.formatted(service.url()));

        run("When I send a GET request to \"/orders\" on the HTTP service \"shop\"\n");

        // Base64 of "ada:pa55"
        assertThat(service.received().getFirst().headers()).containsEntry("authorization", "Basic YWRhOnBhNTU=");
    }

    @Test
    void headersOfTheStepAndOfTheScenarioAddToThoseOfTheSource() throws IOException {
        serve(request -> Reply.status(200));
        sources("""
                url: %s
                headers:
                  X-Client: bdd
                  Accept: text/plain
                """.formatted(service.url()));

        assertThat(run("""
                Given the requests to the HTTP service "shop" have the headers
                  | header           | value     |
                  | X-Correlation-Id | ${uuid:c} |
                  | Accept           | text/csv  |
                When I send a GET request to "/orders" on the HTTP service "shop"
                  | header | value            |
                  | Accept | application/json |
                And I send a DELETE request to "/orders/1" on the HTTP service "shop"
                """).successful()).isTrue();

        List<Request> received = service.received();
        assertThat(received.get(0).headers())
                .containsEntry("x-client", "bdd")
                .containsEntry("accept", "application/json")
                .containsKey("x-correlation-id");
        assertThat(received.get(1).headers())
                .containsEntry("accept", "text/csv")
                .containsEntry("x-correlation-id", received.get(0).headers().get("x-correlation-id"));
    }

    @Test
    void headersOfAScenarioDoNotReachTheNext() throws IOException {
        serve(request -> Reply.status(200));
        sources("url: " + service.url());
        Files.createDirectories(workspace.resolve("features"));
        Files.writeString(workspace.resolve("features/service.feature"), """
                Feature: Service
                  Scenario: With a header
                    Given the requests to the HTTP service "shop" have the headers
                      | header  | value |
                      | X-First | yes   |
                    When I send a GET request to "/one" on the HTTP service "shop"
                  Scenario: Without
                    When I send a GET request to "/two" on the HTTP service "shop"
                """);

        assertThat(Engine.run(new ExecutionRequest(workspace, "local", environment)).successful()).isTrue();
        assertThat(service.received().get(0).headers()).containsKey("x-first");
        assertThat(service.received().get(1).headers()).doesNotContainKey("x-first");
    }

    @Test
    void headerTableNeedsItsTitleRow() throws IOException {
        serve(request -> Reply.status(200));
        sources("url: " + service.url());

        assertThat(lastStep("""
                When I send a GET request to "/orders" on the HTTP service "shop"
                  | Accept | application/json |
                """).error()).isEqualTo("The table of headers must start with the row | header | value |, "
                + "but starts with | Accept | application/json |");
        assertThat(service.received()).isEmpty();
    }

    @Test
    void headersAreGivenAsATableNotAsADocString() throws IOException {
        sources("url: http://localhost:1");

        assertThat(lastStep("""
                Given the requests to the HTTP service "shop" have the headers
                  ""\"
                  Accept: application/json
                  ""\"
                """).error()).isEqualTo("This step takes a table, not a doc string");
    }

    // Read-only sources

    @Test
    void methodsThatModifyDataAreBlockedOnAReadOnlySource() throws IOException {
        serve(request -> Reply.status(200));
        sources("url: " + service.url(), true);

        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            assertThat(lastStep("When I send a " + method + " request to \"/orders\" on the HTTP service \"shop\"\n")
                    .error()).isEqualTo("Source 'shop' of profile 'local' is read-only: this step writes to it, "
                    + "so it was not executed");
        }
        assertThat(service.received()).isEmpty();

        assertThat(run("""
                When I send a GET request to "/orders" on the HTTP service "shop"
                And I send a HEAD request to "/orders" on the HTTP service "shop"
                And I send an OPTIONS request to "/orders" on the HTTP service "shop"
                And I send GET requests to "/orders" on the HTTP service "shop" until the response matches
                """).successful()).isTrue();
        assertThat(service.received()).extracting(Request::method).containsExactly("GET", "HEAD", "OPTIONS", "GET");
    }

    // Timeouts and errors

    @Test
    void responseTimeoutFailsTheStepWithAClearError() throws IOException {
        serve(request -> Reply.status(200).after(2_000));
        sources("""
                url: %s
                responseTimeoutMillis: 200
                """.formatted(service.url()));

        assertThat(lastStep("When I send a GET request to \"/slow\" on the HTTP service \"shop\"\n").error())
                .isEqualTo("The HTTP service 'shop' did not respond to GET " + service.url()
                        + "/slow within 200 ms");
        assertThat(elapsed).isLessThan(Duration.ofMillis(1_500));
    }

    @Test
    void unreachableServiceFailsTheStepWithAClearError() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        sources("""
                url: http://localhost:%d
                connectTimeoutMillis: 500
                """.formatted(port));

        assertThat(lastStep("When I send a GET request to \"/orders\" on the HTTP service \"shop\"\n").error())
                .startsWith("Could not connect to the HTTP service 'shop' at http://localhost:" + port);
    }

    @Test
    void invalidConfigurationIsRejectedBeforeRunning() throws IOException {
        sources("""
                url: http://localhost:1
                headers: Bearer x
                """);

        assertThatThrownBy(() -> run("When I wait 0 seconds\n"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Invalid sources.yaml: profiles.local.sources.shop.config.headers: "
                        + "expected a mapping of names to texts but was 'Bearer x'");
    }

    @Test
    void baseUrlMustBeAnHttpAddress() throws IOException {
        sources("url: localhost:8080");

        assertThat(lastStep("When I send a GET request to \"/orders\" on the HTTP service \"shop\"\n").error())
                .isEqualTo("The url of the HTTP service 'shop' must be an absolute http or https address, "
                        + "but is 'localhost:8080'");
    }

    // Verification of the response

    @Test
    void statusOfTheLastResponseIsVerified() throws IOException {
        serve(request -> Reply.json(201, ORDER));
        sources("url: " + service.url());
        String post = "When I send a POST request to \"/orders\" on the HTTP service \"shop\"\n";

        assertThat(run(post + "Then the response of the HTTP service \"shop\" has status 201\n").successful())
                .isTrue();
        assertThat(lastStep(post + "Then the response of the HTTP service \"shop\" has status 200\n").error())
                .isEqualTo("Expected the response of the HTTP service 'shop' to have status 200, but it has 201");
    }

    @Test
    void statusHeadersAndBodyAreVerifiedWithTheUnifiedTable() throws IOException {
        serve(request -> Reply.json(201, ORDER).withHeader("Location", "/orders/42"));
        sources("url: " + service.url());

        assertThat(run("""
                When I send a POST request to "/orders" on the HTTP service "shop"
                Then the response of the HTTP service "shop" matches
                  | field                | operator | value          |
                  | status               | equals   | 201            |
                  | headers.location     | regex    | ^/orders/\\d+$ |
                  | headers.content-type | contains | json           |
                  | body.status          | equals   | NEW            |
                  | $.body.lines[*].sku  | contains | A1             |
                """).successful()).isTrue();
        String error = lastStep("""
                When I send a POST request to "/orders" on the HTTP service "shop"
                Then the response of the HTTP service "shop" matches
                  | field       | operator | value |
                  | body.status | equals   | PAID  |
                """).error();
        assertThat(error).startsWith("Expected a matching record, but 0 of the 1 observed records match\n"
                + "Observed records:\n  {\"status\":201,\"headers\":{");
        assertThat(error).contains("\"body\":{\"id\":42,\"status\":\"NEW\"");
    }

    @Test
    void verificationOfTheResponseFailsAtOnceSinceItCannotChange() throws IOException {
        serve(request -> Reply.json(200, ORDER));
        sources("url: " + service.url());

        assertThat(lastStep("""
                When I send a GET request to "/orders/42" on the HTTP service "shop"
                Then the response of the HTTP service "shop" matches
                  | field       | operator | value |
                  | body.status | equals   | PAID  |
                """).status()).isEqualTo(Status.FAILED);
        // The default timeout of a verification is 10 seconds.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void bodyIsComparedWithAJsonStrictlyOrPartiallyIgnoringVolatileFields() throws IOException {
        serve(request -> Reply.json(200, ORDER));
        sources("url: " + service.url());
        String get = "When I send a GET request to \"/orders/42\" on the HTTP service \"shop\"\n";

        assertThat(run(get + """
                Then the response body of the HTTP service "shop" equals the JSON ignoring "id, createdAt"
                  ""\"
                  {"status": "NEW", "lines": [{"sku": "A1"}]}
                  ""\"
                And the response body of the HTTP service "shop" contains the JSON
                  ""\"
                  {"id": 42}
                  ""\"
                """).successful()).isTrue();
        assertThat(lastStep(get + """
                Then the response body of the HTTP service "shop" equals the JSON
                  ""\"
                  {"id": 42}
                  ""\"
                """).error()).startsWith("Expected the observed record to equal the JSON, but");
    }

    @Test
    void responseWithoutJsonBodyHasItsTextAsBody() throws IOException {
        serve(request -> new Reply(200, Map.of("Content-Type", "text/plain"), "pong", 0));
        sources("url: " + service.url());

        assertThat(run("""
                When I send a GET request to "/ping" on the HTTP service "shop"
                Then the response of the HTTP service "shop" matches
                  | field | operator | value |
                  | body  | equals   | pong  |
                """).successful()).isTrue();
    }

    @Test
    void verifyingWithoutARequestInTheScenarioFails() throws IOException {
        sources("url: http://localhost:1");

        assertThat(lastStep("Then the response of the HTTP service \"shop\" has status 200\n").error())
                .isEqualTo("No request was sent to the HTTP service 'shop' in this scenario");
    }

    @Test
    void responseOfAScenarioIsNotVerifiedByTheNext() throws IOException {
        serve(request -> Reply.status(200));
        sources("url: " + service.url());
        Files.createDirectories(workspace.resolve("features"));
        Files.writeString(workspace.resolve("features/service.feature"), """
                Feature: Service
                  Scenario: Calling
                    When I send a GET request to "/orders" on the HTTP service "shop"
                  Scenario: Only verifying
                    Then the response of the HTTP service "shop" has status 200
                """);

        ExecutionResult result = Engine.run(new ExecutionRequest(workspace, "local", environment));

        assertThat(result.scenarios().get(1).steps().getFirst().error())
                .isEqualTo("No request was sent to the HTTP service 'shop' in this scenario");
    }

    @Test
    void maskedFieldsOfTheResponseAreNotShown() throws IOException {
        serve(request -> Reply.json(200, "{\"token\": \"t0k3n\", \"status\": \"NEW\"}"));
        sources("url: " + service.url(), false, "[body.token]");

        String error = lastStep("""
                When I send a GET request to "/session" on the HTTP service "shop"
                Then the response of the HTTP service "shop" matches
                  | field       | operator | value |
                  | body.status | equals   | PAID  |
                """).error();
        assertThat(error).contains("\"token\":\"***\"").doesNotContain("t0k3n");
    }

    // Capture

    @Test
    void fieldOfTheResponseIsSavedForTheStepsThatFollow() throws IOException {
        serve(request -> request.method().equals("POST")
                ? Reply.json(201, ORDER).withHeader("Location", "/orders/42")
                : Reply.json(200, ORDER));
        sources("url: " + service.url());

        assertThat(run("""
                When I send a POST request to "/orders" on the HTTP service "shop"
                And I save the "body.id" of the response of the HTTP service "shop" as "orderId"
                And I save the "headers.location" of the response of the HTTP service "shop" as "location"
                And I send a GET request to "/orders/${orderId}" on the HTTP service "shop"
                And I send a GET request to "${location}" on the HTTP service "shop"
                """).successful()).isTrue();
        assertThat(service.received("GET")).extracting(Request::target).containsExactly("/orders/42", "/orders/42");
    }

    // Polling

    @Test
    void requestIsRepeatedUntilTheResponseSatisfiesTheCondition() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        serve(request -> calls.incrementAndGet() < 3
                ? Reply.json(200, "{\"status\": \"PENDING\"}")
                : Reply.json(200, "{\"status\": \"DONE\", \"id\": 7}"));
        settings("5s", "20ms");
        sources("url: " + service.url());

        assertThat(run("""
                When I send GET requests to "/jobs/1" on the HTTP service "shop" until the response matches
                  | field       | operator | value |
                  | status      | equals   | 200   |
                  | body.status | equals   | DONE  |
                Then the response body of the HTTP service "shop" contains the JSON
                  ""\"
                  {"id": 7}
                  ""\"
                """).successful()).isTrue();
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void repeatedRequestFailsWhenTheConditionIsNeverSatisfiedWithinTheTimeout() throws IOException {
        serve(request -> Reply.json(200, "{\"status\": \"PENDING\"}"));
        settings("30s", "20ms");
        sources("url: " + service.url());

        String error = lastStep("""
                When I send GET requests to "/jobs/1" on the HTTP service "shop" until the response matches within 1 second
                  | field       | operator | value |
                  | body.status | equals   | DONE  |
                """).error();
        assertThat(error).startsWith("Expected a matching record, but 0 of the 1 observed records match "
                + "(after waiting 1 s)");
        assertThat(elapsed).isBetween(Duration.ofMillis(900), Duration.ofSeconds(10));
        assertThat(service.received().size()).isGreaterThan(2);
    }

    // Italian

    @Test
    void italianPhrasesLeadToTheSameActions() throws IOException {
        serve(request -> request.method().equals("POST")
                ? Reply.json(201, ORDER)
                : Reply.json(200, "{\"status\": \"PAID\"}"));
        sources("url: " + service.url());
        Files.createDirectories(workspace.resolve("features"));
        Files.writeString(workspace.resolve("features/servizio.feature"), """
                # language: it
                Funzionalità: Servizio
                  Scenario: Ordine
                    Dato le richieste al servizio HTTP "shop" hanno gli header
                      | header | valore           |
                      | Accept | application/json |
                    Quando invio una richiesta POST a "/orders" sul servizio HTTP "shop"
                      ""\"
                      {"sku": "A1"}
                      ""\"
                    Allora la risposta del servizio HTTP "shop" ha lo status 201
                    E la risposta del servizio HTTP "shop" corrisponde
                      | campo       | operatore | valore |
                      | body.status | uguale    | NEW    |
                    E il corpo della risposta del servizio HTTP "shop" contiene il JSON ignorando "createdAt"
                      ""\"
                      {"id": 42}
                      ""\"
                    E salvo "body.id" della risposta del servizio HTTP "shop" come "id"
                    Quando invio richieste GET a "/orders/${id}" sul servizio HTTP "shop" finché la risposta corrisponde entro 5 secondi
                      | campo       | operatore | valore |
                      | body.status | uguale    | PAID   |
                    E invio una richiesta DELETE a "/orders/${id}" sul servizio HTTP "shop"
                """);

        ExecutionResult result = Engine.run(new ExecutionRequest(workspace, "local", environment));

        assertThat(result.scenarios().getFirst().steps()).extracting(StepResult::status).containsOnly(Status.PASSED);
        assertThat(service.received()).extracting(Request::method, Request::target).containsExactly(
                tuple("POST", "/orders"),
                tuple("GET", "/orders/42"),
                tuple("DELETE", "/orders/42"));
    }

    // Support

    private void serve(Function<Request, Reply> replies) throws IOException {
        service = TestService.start(replies);
    }

    private void sources(String config) throws IOException {
        sources(config, false);
    }

    private void sources(String config, boolean readOnly) throws IOException {
        sources(config, readOnly, null);
    }

    private void sources(String config, boolean readOnly, String mask) throws IOException {
        Files.writeString(workspace.resolve("sources.yaml"), """
                profiles:
                  local:
                    sources:
                      shop:
                        type: http
                        readOnly: %s
                %s        config:
                %s""".formatted(readOnly, mask == null ? "" : "        mask: " + mask + "\n",
                config.indent(10)));
    }

    private void settings(String timeout, String interval) throws IOException {
        Files.writeString(workspace.resolve("settings.yaml"), """
                verification:
                  timeout: %s
                  pollInterval: %s
                """.formatted(timeout, interval));
    }

    private ExecutionResult run(String steps) throws IOException {
        Files.createDirectories(workspace.resolve("features"));
        Files.writeString(workspace.resolve("features/service.feature"),
                "Feature: Service\n  Scenario: Calls\n" + steps.indent(4));
        long start = System.nanoTime();
        ExecutionResult result = Engine.run(new ExecutionRequest(workspace, "local", environment));
        elapsed = Duration.ofNanos(System.nanoTime() - start);
        return result;
    }

    /** The last step that ran, or the first that did not. */
    private StepResult lastStep(String steps) throws IOException {
        List<StepResult> results = run(steps).scenarios().getFirst().steps();
        return results.stream()
                .filter(step -> step.status() != Status.PASSED)
                .findFirst()
                .orElse(results.getLast());
    }
}
