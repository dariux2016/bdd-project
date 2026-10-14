package io.github.bdd.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path workspace;

    private FeatureServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void startServer() throws IOException {
        Files.writeString(workspace.resolve("sources.yaml"), """
                profiles:
                  local:
                    sources:
                      fruit:
                        type: fake
                """);
        feature("orders/basket.feature", """
                @smoke
                Feature: Basket
                  Scenario: Adding
                    Given the fake store "fruit" has "apples" set to 5
                    Then the fake store "fruit" should have "apples" equal to 5

                  Scenario: Wrong total
                    Given the fake store "fruit" has "pears" set to 1
                    Then the fake store "fruit" should have "pears" equal to 2
                """);
        feature("top.feature", """
                Feature: Top
                  @fast
                  Scenario: Quick
                    Given the fake store "fruit" has "plums" set to 1
                """);
        server = new FeatureServer(workspace, 0).start();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private void feature(String name, String content) throws IOException {
        Path file = workspace.resolve("features").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private record Reply(int status, JsonNode body) {
    }

    private Reply call(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path));
        request.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), JSON.readTree(response.body()));
    }

    /** One event received on the stream. */
    private record Event(String name, JsonNode data) {
    }

    /** Reads the event stream of the latest execution until it ends. */
    private List<Event> streamToTheEnd() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port()
                + "/api/runs/events")).timeout(Duration.ofSeconds(30)).build();
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        List<Event> events = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String name = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event: ")) {
                    name = line.substring("event: ".length());
                } else if (line.startsWith("data: ")) {
                    events.add(new Event(name, JSON.readTree(line.substring("data: ".length()))));
                }
            }
        }
        return events;
    }

    private static List<String> names(List<Event> events) {
        return events.stream().map(Event::name).toList();
    }

    @Test
    void startedExecutionStreamsTheOutcomeOfEveryFeatureScenarioAndStep() throws Exception {
        Reply started = call("POST", "/api/runs", "{\"profile\":\"local\",\"paths\":[\"orders/basket.feature\"]}");

        assertThat(started.status()).isEqualTo(202);
        List<Event> events = streamToTheEnd();

        assertThat(names(events)).containsExactly(
                "run-started",
                "feature-started",
                "scenario-started", "step-started", "step-finished", "step-started", "step-finished",
                "scenario-finished",
                "scenario-started", "step-started", "step-finished", "step-started", "step-finished",
                "scenario-finished",
                "feature-finished",
                "run-finished");
        assertThat(events.get(1).data().get("feature").asText()).isEqualTo("orders/basket.feature");
        Event failedStep = events.get(12);
        assertThat(failedStep.data().get("status").asText()).isEqualTo("failed");
        assertThat(failedStep.data().get("text").asText()).contains("\"pears\" equal to 2");
        assertThat(failedStep.data().get("error").asText()).contains("Expected \"pears\" to equal 2 but was 1");
        assertThat(events.get(13).data().get("status").asText()).isEqualTo("failed");
        assertThat(events.get(14).data().get("status").asText()).isEqualTo("failed");
        Event finished = events.getLast();
        assertThat(finished.data().get("status").asText()).isEqualTo("failed");
        assertThat(finished.data().get("scenarios").asInt()).isEqualTo(2);
        assertThat(finished.data().get("reports").asText()).startsWith("reports/");
    }

    @Test
    void selectionByTagRunsOnlyTheTaggedScenarios() throws Exception {
        call("POST", "/api/runs", "{\"profile\":\"local\",\"tags\":\"@fast\"}");

        List<Event> events = streamToTheEnd();

        assertThat(events).filteredOn(event -> event.name().equals("scenario-started"))
                .extracting(event -> event.data().get("name").asText()).containsExactly("Quick");
        assertThat(events.getLast().data().get("status").asText()).isEqualTo("passed");
    }

    @Test
    void whoOpensTheStreamAfterTheEndSeesTheWholeExecutionAndItsState() throws Exception {
        call("POST", "/api/runs", "{\"profile\":\"local\",\"tags\":\"@fast\"}");
        streamToTheEnd();

        List<Event> again = streamToTheEnd();
        Reply current = call("GET", "/api/runs/current", null);

        assertThat(names(again)).startsWith("run-started").endsWith("run-finished");
        assertThat(current.status()).isEqualTo(200);
        assertThat(current.body().get("run").get("status").asText()).isEqualTo("passed");
    }

    @Test
    void thereIsNoCurrentExecutionBeforeTheFirstOne() throws Exception {
        Reply current = call("GET", "/api/runs/current", null);

        assertThat(current.status()).isEqualTo(200);
        assertThat(current.body().get("run").isNull()).isTrue();
        assertThat(call("GET", "/api/runs/events", null).status()).isEqualTo(404);
    }

    @Test
    void secondStartWhileAnExecutionRunsIsRefusedAndTheRunningOneCanBeCancelled() throws Exception {
        feature("slow.feature", """
                Feature: Slow
                  Scenario: Waits
                    Then a record exists in the fake store "fruit" within 30 seconds
                """);
        assertThat(call("POST", "/api/runs", "{\"profile\":\"local\",\"paths\":[\"slow.feature\"]}").status())
                .isEqualTo(202);

        Reply current = call("GET", "/api/runs/current", null);
        assertThat(current.body().get("run").get("status").asText()).isEqualTo("running");
        Reply second = call("POST", "/api/runs", "{\"profile\":\"local\"}");
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.body().get("error").asText()).contains("already running");

        assertThat(call("POST", "/api/runs/cancel", null).status()).isEqualTo(202);
        List<Event> events = streamToTheEnd();

        assertThat(events.getLast().name()).isEqualTo("run-finished");
        assertThat(events.getLast().data().get("status").asText()).isEqualTo("cancelled");
        assertThat(call("POST", "/api/runs/cancel", null).status()).isEqualTo(409);
        // The instance accepts a new execution once the previous one ended.
        assertThat(call("POST", "/api/runs", "{\"profile\":\"local\",\"tags\":\"@fast\"}").status()).isEqualTo(202);
    }

    @Test
    void startupErrorsAreReportedToTheCaller() throws Exception {
        Reply unknownProfile = call("POST", "/api/runs", "{\"profile\":\"nowhere\"}");
        Reply unknownPath = call("POST", "/api/runs", "{\"profile\":\"local\",\"paths\":[\"missing.feature\"]}");
        Reply badTags = call("POST", "/api/runs", "{\"profile\":\"local\",\"tags\":\"@a and\"}");

        assertThat(unknownProfile.status()).isEqualTo(400);
        assertThat(unknownProfile.body().get("error").asText()).contains("nowhere");
        assertThat(unknownPath.status()).isEqualTo(400);
        assertThat(unknownPath.body().get("error").asText()).contains("missing.feature");
        assertThat(badTags.status()).isEqualTo(400);
        // A refused start does not hold the instance.
        assertThat(call("POST", "/api/runs", "{\"profile\":\"local\",\"tags\":\"@fast\"}").status()).isEqualTo(202);
    }
}
