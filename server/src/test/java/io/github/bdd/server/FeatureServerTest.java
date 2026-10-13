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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

class FeatureServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path workspace;

    private FeatureServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void startServer() throws IOException {
        Files.createDirectories(workspace.resolve("features/orders"));
        Files.writeString(workspace.resolve("features/orders/basket.feature"), "Feature: Basket\n");
        Files.writeString(workspace.resolve("features/top.feature"), "Feature: Top\n");
        Files.writeString(workspace.resolve("secret.txt"), "outside");
        server = new FeatureServer(workspace, 0).start();
    }

    @AfterEach
    void stopServer() {
        server.close();
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

    private static String q(String path) {
        return "?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8);
    }

    private static String json(Object... pairs) throws Exception {
        var node = JSON.createObjectNode();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] instanceof Boolean b) {
                node.put((String) pairs[i], b);
            } else {
                node.put((String) pairs[i], (String) pairs[i + 1]);
            }
        }
        return JSON.writeValueAsString(node);
    }

    @Test
    void treeListsFoldersBeforeFilesUnderTheFeaturesDirectory() throws Exception {
        Reply reply = call("GET", "/api/tree", null);

        assertThat(reply.status()).isEqualTo(200);
        JsonNode children = reply.body().get("children");
        assertThat(children.get(0).get("name").asText()).isEqualTo("orders");
        assertThat(children.get(0).get("type").asText()).isEqualTo("folder");
        assertThat(children.get(0).get("children").get(0).get("path").asText()).isEqualTo("orders/basket.feature");
        assertThat(children.get(1).get("name").asText()).isEqualTo("top.feature");
    }

    @Test
    void readReturnsTheContentAndAVersion() throws Exception {
        Reply reply = call("GET", "/api/file" + q("orders/basket.feature"), null);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body().get("content").asText()).isEqualTo("Feature: Basket\n");
        assertThat(reply.body().get("version").asText()).isNotBlank();
    }

    @Test
    void writeSavesTheContentOnDiskWhenTheVersionMatches() throws Exception {
        String version = call("GET", "/api/file" + q("top.feature"), null).body().get("version").asText();

        Reply reply = call("PUT", "/api/file" + q("top.feature"),
                json("content", "Feature: Changed\n", "version", version));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(Files.readString(workspace.resolve("features/top.feature"))).isEqualTo("Feature: Changed\n");
        assertThat(reply.body().get("version").asText()).isNotEqualTo(version);
    }

    @Test
    void writeIsRejectedWithConflictWhenTheFileChangedOnDiskSinceItWasOpened() throws Exception {
        String version = call("GET", "/api/file" + q("top.feature"), null).body().get("version").asText();
        Files.writeString(workspace.resolve("features/top.feature"), "Feature: Edited elsewhere\n");

        Reply reply = call("PUT", "/api/file" + q("top.feature"),
                json("content", "Feature: Mine\n", "version", version));

        assertThat(reply.status()).isEqualTo(409);
        assertThat(Files.readString(workspace.resolve("features/top.feature")))
                .isEqualTo("Feature: Edited elsewhere\n");
    }

    @Test
    void writeWithOverwriteReplacesTheFileDespiteTheConflict() throws Exception {
        String version = call("GET", "/api/file" + q("top.feature"), null).body().get("version").asText();
        Files.writeString(workspace.resolve("features/top.feature"), "Feature: Edited elsewhere\n");

        Reply reply = call("PUT", "/api/file" + q("top.feature"),
                json("content", "Feature: Mine\n", "version", version, "overwrite", true));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(Files.readString(workspace.resolve("features/top.feature"))).isEqualTo("Feature: Mine\n");
    }

    @Test
    void createMakesNewFilesAndFoldersAndRefusesExistingOnes() throws Exception {
        assertThat(call("POST", "/api/file" + q("new/deep.feature"), json("content", "Feature: New\n")).status())
                .isEqualTo(201);
        assertThat(Files.readString(workspace.resolve("features/new/deep.feature"))).isEqualTo("Feature: New\n");

        assertThat(call("POST", "/api/folder" + q("empty"), null).status()).isEqualTo(201);
        assertThat(workspace.resolve("features/empty")).isDirectory();

        assertThat(call("POST", "/api/file" + q("top.feature"), json("content", "x")).status()).isEqualTo(409);
        assertThat(Files.readString(workspace.resolve("features/top.feature"))).isEqualTo("Feature: Top\n");
    }

    @Test
    void renameMovesFilesAndFolders() throws Exception {
        assertThat(call("POST", "/api/rename", json("from", "top.feature", "to", "orders/renamed.feature")).status())
                .isEqualTo(200);
        assertThat(workspace.resolve("features/orders/renamed.feature")).exists();
        assertThat(workspace.resolve("features/top.feature")).doesNotExist();

        assertThat(call("POST", "/api/rename", json("from", "orders", "to", "sales")).status()).isEqualTo(200);
        assertThat(workspace.resolve("features/sales/basket.feature")).exists();
    }

    @Test
    void deleteRemovesFilesAndFoldersRecursively() throws Exception {
        assertThat(call("DELETE", "/api/entry" + q("top.feature"), null).status()).isEqualTo(200);
        assertThat(workspace.resolve("features/top.feature")).doesNotExist();

        assertThat(call("DELETE", "/api/entry" + q("orders"), null).status()).isEqualTo(200);
        assertThat(workspace.resolve("features/orders")).doesNotExist();

        assertThat(call("DELETE", "/api/entry" + q("missing.feature"), null).status()).isEqualTo(404);
    }

    @Test
    void readingOrWritingOutsideTheFeaturesDirectoryIsRefused() throws Exception {
        String absolute = workspace.resolve("secret.txt").toString();

        assertThat(call("GET", "/api/file" + q("../secret.txt"), null).status()).isEqualTo(400);
        assertThat(call("GET", "/api/file" + q("orders/../../secret.txt"), null).status()).isEqualTo(400);
        assertThat(call("GET", "/api/file" + q(absolute), null).status()).isEqualTo(400);
        assertThat(call("GET", "/api/file" + q("/etc/passwd"), null).status()).isEqualTo(400);
        assertThat(call("GET", "/api/file" + q("..\\secret.txt"), null).status()).isEqualTo(400);

        assertThat(call("PUT", "/api/file" + q("../secret.txt"),
                json("content", "pwned", "overwrite", true)).status()).isEqualTo(400);
        assertThat(call("POST", "/api/file" + q("../created.feature"), json("content", "x")).status())
                .isEqualTo(400);
        assertThat(call("POST", "/api/folder" + q("../created"), null).status()).isEqualTo(400);
        assertThat(call("POST", "/api/rename", json("from", "top.feature", "to", "../moved.feature")).status())
                .isEqualTo(400);
        assertThat(call("POST", "/api/rename", json("from", "../secret.txt", "to", "stolen.feature")).status())
                .isEqualTo(400);
        assertThat(call("DELETE", "/api/entry" + q("../secret.txt"), null).status()).isEqualTo(400);

        assertThat(Files.readString(workspace.resolve("secret.txt"))).isEqualTo("outside");
        assertThat(workspace.resolve("created.feature")).doesNotExist();
        assertThat(workspace.resolve("moved.feature")).doesNotExist();
    }

    @Test
    void theFeaturesDirectoryItselfCannotBeDeletedOrRenamed() throws Exception {
        assertThat(call("DELETE", "/api/entry" + q(""), null).status()).isEqualTo(400);
        assertThat(call("POST", "/api/rename", json("from", "", "to", "other")).status()).isEqualTo(400);
        assertThat(workspace.resolve("features")).isDirectory();
    }

    @Test
    void symbolicLinksPointingOutsideTheFeaturesDirectoryAreRefused() throws Exception {
        Path outsideDir = Files.createDirectories(workspace.resolve("outside"));
        Files.writeString(outsideDir.resolve("hidden.feature"), "Feature: Hidden\n");
        try {
            Files.createSymbolicLink(workspace.resolve("features/link"), outsideDir);
            Files.createSymbolicLink(workspace.resolve("features/file-link.feature"),
                    outsideDir.resolve("hidden.feature"));
        } catch (IOException | UnsupportedOperationException e) {
            assumeThat(false).as("symbolic links are not available: " + e).isTrue();
        }

        assertThat(call("GET", "/api/file" + q("link/hidden.feature"), null).status()).isEqualTo(400);
        assertThat(call("GET", "/api/file" + q("file-link.feature"), null).status()).isEqualTo(400);
        assertThat(call("PUT", "/api/file" + q("link/hidden.feature"),
                json("content", "pwned", "overwrite", true)).status()).isEqualTo(400);
        assertThat(call("POST", "/api/file" + q("link/new.feature"), json("content", "x")).status())
                .isEqualTo(400);
        assertThat(call("DELETE", "/api/entry" + q("link/hidden.feature"), null).status()).isEqualTo(400);
        assertThat(Files.readString(outsideDir.resolve("hidden.feature"))).isEqualTo("Feature: Hidden\n");
        assertThat(outsideDir.resolve("new.feature")).doesNotExist();
        assertThat(call("GET", "/api/tree", null).body().toString()).doesNotContain("link");
    }

    @Test
    void theUiIsServedFromTheSameServer() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("<textarea");
    }
}
