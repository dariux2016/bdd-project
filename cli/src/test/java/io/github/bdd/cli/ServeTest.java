package io.github.bdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ServeTest {

    @TempDir
    Path workspace;

    @Test
    void serveStartsTheServerOnTheWorkspaceAndReportsItsAddress() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = Serve.execute(new String[] {"serve", workspace.toString(), "--port", "0"},
                new PrintStream(out), new PrintStream(err));

        assertThat(code).isEqualTo(-1);
        Matcher address = Pattern.compile("http://localhost:(\\d+)").matcher(out.toString());
        assertThat(address.find()).isTrue();
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + address.group(1) + "/api/tree")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(workspace.resolve("features")).isDirectory();
    }

    @Test
    void serveRejectsAnInvalidPort() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = Serve.execute(new String[] {"serve", "--port", "abc"},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));

        assertThat(code).isEqualTo(2);
        assertThat(err.toString()).contains("Usage: serve");
    }
}
