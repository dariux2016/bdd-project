package io.github.bdd.connector.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bdd.plugin.Source;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** An HTTP service as a source configures it: where it is, how to reach it and what every request carries. */
final class Service {

    static final String URL = "url";
    static final String HEADERS = "headers";
    static final String USERNAME = "username";
    static final String PASSWORD = "password";
    static final String CONNECT_TIMEOUT = "connectTimeoutMillis";
    static final String RESPONSE_TIMEOUT = "responseTimeoutMillis";

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(30);
    /** The characters a URI may hold as they are; any other is percent-encoded. */
    private static final String LEGAL = "-._~:/?#[]@!$&'()*+,;=%";

    private final String name;
    private final String base;
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Duration responseTimeout;
    private final Duration connectTimeout;
    private final HttpClient client;
    private final ObjectMapper json;

    @SuppressWarnings("unchecked")
    Service(Source source, ObjectMapper json) {
        this.name = source.name();
        this.json = json;
        String url = (String) source.config().get(URL);
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            uri = null;
        }
        if (uri == null || uri.getHost() == null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("The url of the HTTP service '" + name
                    + "' must be an absolute http or https address, but is '" + url + "'");
        }
        this.base = url.strip().replaceAll("/+$", "");

        Object username = source.config().get(USERNAME);
        Object password = source.config().get(PASSWORD);
        if ((username == null) != (password == null)) {
            throw new IllegalArgumentException("The HTTP service '" + name + "' needs both " + USERNAME
                    + " and " + PASSWORD + " to authenticate, or neither");
        }
        if (username != null) {
            headers.put("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8)));
        }
        headers.putAll((Map<String, String>) source.config().getOrDefault(HEADERS, Map.of()));

        this.connectTimeout = millis(source, CONNECT_TIMEOUT, DEFAULT_CONNECT_TIMEOUT);
        this.responseTimeout = millis(source, RESPONSE_TIMEOUT, DEFAULT_RESPONSE_TIMEOUT);
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private Duration millis(Source source, String property, Duration byDefault) {
        Integer value = (Integer) source.config().get(property);
        if (value == null) {
            return byDefault;
        }
        if (value <= 0) {
            throw new IllegalArgumentException("The " + property + " of the HTTP service '" + name
                    + "' must be more than zero, but is " + value);
        }
        return Duration.ofMillis(value);
    }

    /**
     * Sends a request and waits for the whole response.
     *
     * @param path the path under the base URL, with the query string if any
     * @param extraHeaders added to those of the source, replacing any of the same name
     * @param body {@code null} for a request without body
     */
    Response send(String method, String path, Map<String, String> extraHeaders, String body) throws IOException {
        URI uri = uri(path);
        Map<String, String> all = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        all.putAll(headers);
        all.putAll(extraHeaders);
        if (body != null && !all.containsKey("Content-Type")) {
            all.put("Content-Type", isJson(body) ? "application/json" : "text/plain; charset=UTF-8");
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(responseTimeout)
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        all.forEach((header, value) -> {
            try {
                request.header(header, value);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("The header '" + header + "' cannot be set on a request to the "
                        + "HTTP service '" + name + "': " + e.getMessage());
            }
        });

        HttpResponse<String> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException e) {
            throw new IOException("Could not connect to the HTTP service '" + name + "' at " + base + " within "
                    + connectTimeout.toMillis() + " ms", e);
        } catch (HttpTimeoutException e) {
            throw new IOException("The HTTP service '" + name + "' did not respond to " + method + " " + uri
                    + " within " + responseTimeout.toMillis() + " ms", e);
        } catch (ConnectException e) {
            throw new IOException("Could not connect to the HTTP service '" + name + "' at " + base
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()), e);
        } catch (IOException e) {
            throw new IOException("The request " + method + " " + uri + " to the HTTP service '" + name
                    + "' failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The execution was interrupted while waiting for the HTTP service '"
                    + name + "'");
        }
        return response(response);
    }

    private URI uri(String path) {
        String target = path.strip();
        String joined = base + (target.isEmpty() || target.startsWith("/") || target.startsWith("?") ? "" : "/")
                + target;
        try {
            return URI.create(encoded(joined));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + path + "' is not a valid path for the HTTP service '" + name
                    + "': " + e.getMessage());
        }
    }

    /** The text with the characters a URI cannot hold, such as spaces, percent-encoded. */
    private static String encoded(String text) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if (c < 0x80 && (Character.isLetterOrDigit(c) || LEGAL.indexOf(c) >= 0)) {
                encoded.append(c);
            } else {
                encoded.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return encoded.toString();
    }

    private boolean isJson(String text) {
        String trimmed = text.strip();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return false;
        }
        try {
            json.readTree(trimmed);
            return true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private Response response(HttpResponse<String> response) {
        Map<String, Object> headers = new LinkedHashMap<>();
        response.headers().map().forEach((header, values) -> {
            if (!header.startsWith(":")) {
                headers.put(header.toLowerCase(Locale.ROOT), String.join(", ", values));
            }
        });
        return new Response(response.statusCode(), headers, body(response.body(), (String) headers.get("content-type")));
    }

    /** The body as JSON when it is JSON, or else as text; {@code null} if there is none. */
    private Object body(String text, String contentType) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        boolean declaredJson = contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json");
        if (declaredJson || contentType == null) {
            try {
                return json.readValue(text, Object.class);
            } catch (JsonProcessingException e) {
                // Not JSON after all: kept as text.
            }
        }
        return text;
    }

    /**
     * A response of the service.
     *
     * @param headers by lowercase name, with the values of a repeated header joined by commas
     * @param body the JSON of the body, or its text if it is not JSON; {@code null} if empty
     */
    record Response(int status, Map<String, Object> headers, Object body) {

        /** The response as the record the engine verifies. */
        Map<String, Object> record() {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("status", status);
            record.put("headers", headers);
            record.put("body", body);
            return record;
        }

        /** The body as the record the engine compares with a JSON. */
        List<?> bodyRecord() {
            return java.util.Collections.singletonList(body);
        }
    }
}
