package io.github.bdd.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path workspace;

    private FeatureServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void startServer() throws IOException {
        Files.createDirectories(workspace.resolve("features"));
        server = new FeatureServer(workspace, 0).start();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private record Reply(int status, JsonNode body) {
    }

    private Reply get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path)).build());
    }

    private Reply validate(String content) throws Exception {
        String body = JSON.writeValueAsString(JSON.createObjectNode().put("content", content));
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/api/validate"))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private Reply send(HttpRequest request) throws Exception {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), JSON.readTree(response.body()));
    }

    private static String q(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.path(field).asText()));
        return values;
    }

    @Test
    void validationSaysForEachStepWhichPhraseMatchesOrWhy() throws Exception {
        Reply reply = validate("""
                Feature: Store
                  Scenario: Typo
                    Given the fake store "s" has "k" set to 3
                    When the basket is emptied
                """);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body().get("language").asText()).isEqualTo("en");
        assertThat(reply.body().get("errors")).isEmpty();
        JsonNode steps = reply.body().get("steps");
        assertThat(texts(steps, "status")).containsExactly("matched", "undefined");
        assertThat(steps.get(0).get("line").asInt()).isEqualTo(3);
        assertThat(steps.get(0).get("phrase").asText()).isEqualTo("the fake store {source} has {key} set to {value}");
        assertThat(steps.get(1).get("line").asInt()).isEqualTo(4);
        assertThat(steps.get(1).get("message").asText()).contains("No phrase");
    }

    @Test
    void validationReportsSyntaxErrorsWithLineAndMessage() throws Exception {
        Reply reply = validate("Feature: Broken\n  Scenario: Broken\n    Given something\n  not gherkin\n");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body().get("errors").get(0).get("line").asInt()).isEqualTo(4);
        assertThat(reply.body().get("errors").get(0).get("message").asText()).isNotBlank();
    }

    @Test
    void validationUsesTheAliasesOfTheWorkspace() throws Exception {
        Files.writeString(workspace.resolve("catalog.yaml"), """
                aliases:
                  - phrase: "{key} of the fake store {source} is {value}"
                    for: "the fake store {source} has {key} set to {value}"
                """);

        Reply reply = validate("Feature: A\n  Scenario: B\n    Given \"k\" of the fake store \"s\" is 3\n");

        assertThat(texts(reply.body().get("steps"), "status")).containsExactly("matched");
        assertThat(reply.body().get("steps").get(0).get("origin").asText()).isEqualTo("workspace");
    }

    @Test
    void validationWithoutContentIsABadRequest() throws Exception {
        Reply reply = send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/api/validate"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build());

        assertThat(reply.status()).isEqualTo(400);
    }

    @Test
    void catalogListsPhrasesWithParametersAndOrigin() throws Exception {
        Reply reply = get("/api/catalog");

        assertThat(reply.status()).isEqualTo(200);
        JsonNode put = null;
        for (JsonNode phrase : reply.body().get("phrases")) {
            if (phrase.get("pattern").asText().equals("the fake store {source} has {key} set to {value}")) {
                put = phrase;
            }
        }
        assertThat(put).isNotNull();
        assertThat(put.get("language").asText()).isEqualTo("en");
        assertThat(put.get("origin").asText()).isEqualTo("connector:fake");
        assertThat(put.get("source").asText()).isEqualTo("fake");
        assertThat(texts(put.get("parameters"), "name")).containsExactly("source", "key", "value");
        assertThat(put.get("parameters").get(0).get("type").asText()).isEqualTo("SOURCE");
        assertThat(put.get("template").asText()).isEqualTo("the fake store \"source\" has \"key\" set to 0");
    }

    @Test
    void catalogFiltersBySourceLanguageAndText() throws Exception {
        JsonNode italian = get("/api/catalog?language=it&source=fake").body().get("phrases");
        assertThat(italian).isNotEmpty();
        italian.forEach(phrase -> {
            assertThat(phrase.get("language").asText()).isEqualTo("it");
            assertThat(phrase.get("source").asText()).isEqualTo("fake");
        });

        JsonNode matching = get("/api/catalog?q=" + q("ADDED")).body().get("phrases");
        assertThat(texts(matching, "pattern")).containsExactly("{amount} is added to {key} in the fake store {source}");

        assertThat(get("/api/catalog?source=engine").body().get("phrases")).isNotEmpty();
    }

    @Test
    void theCatalogPageIsServedWithTheUi() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/catalog.html")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("id=\"phrases\"");
    }

    @Test
    void catalogTemplatesKeepTheFirstAlternativeAndThePlural() throws Exception {
        JsonNode phrases = get("/api/catalog?q=" + q("exactly")).body().get("phrases");

        assertThat(texts(phrases, "template"))
                .containsExactly("exactly 0 records exists in the fake store \"source\"");
        JsonNode italianWait = get("/api/catalog?language=it&source=engine").body().get("phrases");
        assertThat(texts(italianWait, "template")).containsExactly("attendo 0 secondo");
    }
}
