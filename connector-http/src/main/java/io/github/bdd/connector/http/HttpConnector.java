package io.github.bdd.connector.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bdd.plugin.Action;
import io.github.bdd.plugin.ActionKind;
import io.github.bdd.plugin.Arguments;
import io.github.bdd.plugin.ConfigProperty;
import io.github.bdd.plugin.ConfigType;
import io.github.bdd.plugin.Connector;
import io.github.bdd.plugin.Parameter;
import io.github.bdd.plugin.ParameterType;
import io.github.bdd.plugin.StepArgument;
import io.github.bdd.plugin.Phrase;
import io.github.bdd.plugin.Source;
import io.github.bdd.plugin.Verification;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Sends requests to HTTP services and exposes the last response of each as a single record
 * <code>{status, headers, body}</code>: the header names are in lowercase, and the body is the
 * parsed JSON when the response has one, its text otherwise.
 *
 * <p>A source is a service: its {@code baseUrl}, the {@code headers} every request carries (one
 * <code>Name: value</code> per line), an {@code authorization} header value, and the
 * {@code connectTimeout} and {@code responseTimeout} in seconds.
 */
public final class HttpConnector implements Connector {

    static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    static final int DEFAULT_RESPONSE_TIMEOUT_SECONDS = 30;

    private static final Parameter SOURCE = new Parameter("source", ParameterType.SOURCE);
    private static final Parameter PATH = new Parameter("path", ParameterType.STRING);
    private static final Parameter NAME = new Parameter("name", ParameterType.STRING);
    private static final Parameter VALUE = new Parameter("value", ParameterType.STRING);
    private static final List<Parameter> REQUEST = List.of(SOURCE, PATH);

    private static final List<List<String>> HEADERS_TABLE_TITLES = List.of(
            List.of("name", "value"), List.of("header", "value"), List.of("nome", "valore"));
    private static final String[] SENT_METHODS = {"POST", "PUT", "PATCH", "DELETE"};

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, HttpClient> clients = new HashMap<>();
    /** By source: what the next request is given by the steps that precede it. */
    private final Map<String, Pending> pending = new HashMap<>();
    /** By source: the response to the last request. */
    private final Map<String, Map<String, Object>> responses = new HashMap<>();

    private static final class Pending {
        final Map<String, String[]> headers = new LinkedHashMap<>();
        final List<String[]> query = new ArrayList<>();
    }

    @Override
    public String sourceType() {
        return "http";
    }

    @Override
    public List<ConfigProperty> configSchema() {
        return List.of(
                new ConfigProperty("baseUrl", ConfigType.STRING, true),
                new ConfigProperty("headers", ConfigType.STRING, false),
                new ConfigProperty("authorization", ConfigType.STRING, false),
                new ConfigProperty("connectTimeout", ConfigType.INTEGER, false),
                new ConfigProperty("responseTimeout", ConfigType.INTEGER, false));
    }

    @Override
    public List<Action> actions() {
        List<Action> actions = new ArrayList<>();
        actions.add(new Action("get", ActionKind.READ, REQUEST, arguments -> send("GET", arguments, true),
                StepArgument.OPTIONAL));
        for (String method : SENT_METHODS) {
            actions.add(new Action(method.toLowerCase(Locale.ROOT), ActionKind.WRITE, REQUEST,
                    arguments -> send(method, arguments, true), StepArgument.OPTIONAL));
        }
        actions.add(new Action("poll", ActionKind.READ, List.of(SOURCE, PATH),
                arguments -> send("GET", arguments, false)));
        actions.add(new Action("header", ActionKind.READ, List.of(SOURCE, NAME, VALUE), this::addHeader));
        actions.add(new Action("query", ActionKind.READ, List.of(SOURCE, NAME, VALUE), this::addQuery));
        actions.add(new Action("response", ActionKind.READ, List.of(SOURCE), this::response));
        actions.add(new Action("responseBody", ActionKind.READ, List.of(SOURCE), this::responseBody));
        return actions;
    }

    @Override
    public List<Phrase> defaultPhrases() {
        List<Phrase> phrases = new ArrayList<>();
        for (String method : new String[] {"GET", "POST", "PUT", "PATCH", "DELETE"}) {
            String action = method.toLowerCase(Locale.ROOT);
            phrases.add(new Phrase("I send a " + method + " request to {path} on the HTTP service {source}", action));
            phrases.add(new Phrase("invio una richiesta " + method + " a {path} sul servizio HTTP {source}", action,
                    null, Phrase.ITALIAN));
        }
        phrases.addAll(List.of(
                new Phrase("the next request to the HTTP service {source} has the header {name} set to {value}",
                        "header"),
                new Phrase("the next request to the HTTP service {source} has the query parameter {name} set to "
                        + "{value}", "query"),
                new Phrase("the last response of the HTTP service {source} satisfies the conditions", "response",
                        Verification.EXISTS),
                new Phrase("the last response of the HTTP service {source} satisfies the conditions within "
                        + "{timeout} second(s)", "response", Verification.EXISTS),
                new Phrase("the body of the last response of the HTTP service {source} equals the JSON",
                        "responseBody", Verification.JSON_STRICT),
                new Phrase("the body of the last response of the HTTP service {source} equals the JSON ignoring "
                        + "{ignored}", "responseBody", Verification.JSON_STRICT),
                new Phrase("the body of the last response of the HTTP service {source} contains the JSON",
                        "responseBody", Verification.JSON_PARTIAL),
                new Phrase("the body of the last response of the HTTP service {source} contains the JSON ignoring "
                        + "{ignored}", "responseBody", Verification.JSON_PARTIAL),
                new Phrase("I save the {field} of the last response of the HTTP service {source} as {variable}",
                        "response", Verification.SAVE),
                new Phrase("I call GET {path} on the HTTP service {source} until the response satisfies the "
                        + "conditions", "poll", Verification.EXISTS),
                new Phrase("I call GET {path} on the HTTP service {source} until the response satisfies the "
                        + "conditions within {timeout} second(s)", "poll", Verification.EXISTS)));

        phrases.addAll(List.of(
                italian("la prossima richiesta al servizio HTTP {source} ha l'header {name} impostato a {value}",
                        "header", null),
                italian("la prossima richiesta al servizio HTTP {source} ha il parametro di query {name} "
                        + "impostato a {value}", "query", null),
                italian("l'ultima risposta del servizio HTTP {source} soddisfa le condizioni", "response",
                        Verification.EXISTS),
                italian("l'ultima risposta del servizio HTTP {source} soddisfa le condizioni entro {timeout} "
                        + "secondo/secondi", "response", Verification.EXISTS),
                italian("il corpo dell'ultima risposta del servizio HTTP {source} è uguale al JSON",
                        "responseBody", Verification.JSON_STRICT),
                italian("il corpo dell'ultima risposta del servizio HTTP {source} è uguale al JSON ignorando "
                        + "{ignored}", "responseBody", Verification.JSON_STRICT),
                italian("il corpo dell'ultima risposta del servizio HTTP {source} contiene il JSON",
                        "responseBody", Verification.JSON_PARTIAL),
                italian("il corpo dell'ultima risposta del servizio HTTP {source} contiene il JSON ignorando "
                        + "{ignored}", "responseBody", Verification.JSON_PARTIAL),
                italian("salvo {field} dell'ultima risposta del servizio HTTP {source} come {variable}",
                        "response", Verification.SAVE),
                italian("chiamo GET {path} sul servizio HTTP {source} finché la risposta soddisfa le condizioni",
                        "poll", Verification.EXISTS),
                italian("chiamo GET {path} sul servizio HTTP {source} finché la risposta soddisfa le condizioni "
                        + "entro {timeout} secondo/secondi", "poll", Verification.EXISTS)));
        return phrases;
    }

    private static Phrase italian(String pattern, String action, Verification verification) {
        return new Phrase(pattern, action, verification, Phrase.ITALIAN);
    }

    // ---- requests ----

    /**
     * @param consume whether the headers and query parameters set by the preceding steps are used up
     *     by this request; a request that is repeated keeps them for its next attempt
     */
    private List<?> send(String method, Arguments arguments, boolean consume) throws IOException {
        Source source = arguments.source("source");
        Pending given = pending.computeIfAbsent(source.name(), name -> new Pending());

        Map<String, String[]> headers = new LinkedHashMap<>();
        defaultHeaders(source).forEach((name, value) -> headers.put(name.toLowerCase(Locale.ROOT),
                new String[] {name, value}));
        headers.putAll(given.headers);
        Optional<List<List<String>>> table = consume ? arguments.optionalTable() : Optional.empty();
        if (table.isPresent()) {
            headersOf(table.get()).forEach((name, value) -> headers.put(name.toLowerCase(Locale.ROOT),
                    new String[] {name, value}));
        }
        Optional<String> body = consume ? arguments.optionalText() : Optional.empty();
        if (body.isPresent() && !headers.containsKey("content-type")) {
            String trimmed = body.get().stripLeading();
            boolean isJson = trimmed.startsWith("{") || trimmed.startsWith("[");
            headers.put("content-type", new String[] {"Content-Type", isJson ? "application/json" : "text/plain"});
        }

        URI uri = uri(source, arguments.string("path"), given.query);
        int responseTimeout = integer(source, "responseTimeout", DEFAULT_RESPONSE_TIMEOUT_SECONDS);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(responseTimeout))
                .method(method, body.isPresent()
                        ? HttpRequest.BodyPublishers.ofString(body.get())
                        : HttpRequest.BodyPublishers.noBody());
        headers.values().forEach(header -> request.header(header[0], header[1]));
        if (consume) {
            pending.remove(source.name());
        }

        HttpResponse<byte[]> response;
        try {
            response = client(source).send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpConnectTimeoutException e) {
            throw new IllegalStateException("Could not connect to " + uri + " within "
                    + integer(source, "connectTimeout", DEFAULT_CONNECT_TIMEOUT_SECONDS)
                    + " second(s): raise the connectTimeout of the source if the service is slow to accept", e);
        } catch (HttpTimeoutException e) {
            throw new IllegalStateException("No response from " + uri + " within " + responseTimeout
                    + " second(s): raise the responseTimeout of the source if the service is slow to answer", e);
        } catch (ConnectException e) {
            throw new IllegalStateException("Could not connect to " + uri + ": " + describe(e), e);
        } catch (IOException e) {
            throw new IllegalStateException("The request " + method + " " + uri + " failed: " + describe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The request " + method + " " + uri + " was interrupted", e);
        }

        Map<String, Object> record = record(response);
        responses.put(source.name(), record);
        return List.of(record);
    }

    private static String describe(IOException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private HttpClient client(Source source) {
        return clients.computeIfAbsent(source.name(), name -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(
                        integer(source, "connectTimeout", DEFAULT_CONNECT_TIMEOUT_SECONDS)))
                .build());
    }

    private static int integer(Source source, String property, int byDefault) {
        return source.config().get(property) instanceof Integer value ? value : byDefault;
    }

    private static URI uri(Source source, String path, List<String[]> query) {
        StringBuilder text = new StringBuilder((String) source.config().get("baseUrl"));
        while (text.length() > 0 && text.charAt(text.length() - 1) == '/') {
            text.setLength(text.length() - 1);
        }
        if (!path.startsWith("/") && !path.isEmpty()) {
            text.append('/');
        }
        text.append(path);
        char separator = path.contains("?") ? '&' : '?';
        for (String[] parameter : query) {
            text.append(separator).append(encode(parameter[0])).append('=').append(encode(parameter[1]));
            separator = '&';
        }
        try {
            return URI.create(text.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + text + "' is not a valid address: " + e.getMessage());
        }
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    /** The headers of the source configuration, with its {@code authorization} as the last of them. */
    private static Map<String, String> defaultHeaders(Source source) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (source.config().get("headers") instanceof String lines) {
            for (String line : lines.split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    throw new IllegalArgumentException("The headers of source '" + source.name()
                            + "' have the line '" + line + "' that is not 'Name: value'");
                }
                headers.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
            }
        }
        if (source.config().get("authorization") instanceof String authorization) {
            headers.put("Authorization", authorization);
        }
        return headers;
    }

    /** The headers of a <code>name | value</code> table, whose first row gives the titles of the columns. */
    private static Map<String, String> headersOf(List<List<String>> rows) {
        List<String> titles = rows.getFirst().stream()
                .map(cell -> cell == null ? "" : cell.strip().toLowerCase(Locale.ROOT)).toList();
        if (!HEADERS_TABLE_TITLES.contains(titles)) {
            throw new IllegalArgumentException("A table of headers has the columns 'name | value' (or "
                    + "'nome | valore') but this one has '" + String.join(" | ", titles) + "'");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            String name = row.get(0) == null ? "" : row.get(0).strip();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("A header of the table has no name");
            }
            headers.put(name, row.get(1) == null ? "" : row.get(1).strip());
        }
        return headers;
    }

    private Map<String, Object> record(HttpResponse<byte[]> response) {
        Map<String, Object> headers = new TreeMap<>();
        response.headers().map().forEach((name, values) ->
                headers.put(name.toLowerCase(Locale.ROOT), String.join(", ", values)));
        String contentType = String.valueOf(headers.getOrDefault("content-type", "")).toLowerCase(Locale.ROOT);
        String text = new String(response.body(), StandardCharsets.UTF_8);

        Object body = text.isEmpty() ? null : text;
        String trimmed = text.strip();
        if (contentType.contains("json") || trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                body = json.readValue(trimmed, Object.class);
            } catch (JsonProcessingException e) {
                // Not a JSON after all: the text is the body.
            }
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("status", response.statusCode());
        record.put("headers", headers);
        record.put("body", body);
        return record;
    }

    // ---- state of the next request and observation of the last response ----

    private List<?> addHeader(Arguments arguments) {
        String name = arguments.string("name");
        pending.computeIfAbsent(arguments.source("source").name(), key -> new Pending()).headers
                .put(name.toLowerCase(Locale.ROOT), new String[] {name, arguments.string("value")});
        return List.of();
    }

    private List<?> addQuery(Arguments arguments) {
        pending.computeIfAbsent(arguments.source("source").name(), key -> new Pending()).query
                .add(new String[] {arguments.string("name"), arguments.string("value")});
        return List.of();
    }

    private List<?> response(Arguments arguments) {
        return List.of(lastResponse(arguments.source("source")));
    }

    private List<?> responseBody(Arguments arguments) {
        Object body = lastResponse(arguments.source("source")).get("body");
        if (body == null) {
            throw new AssertionError("The last response has no body");
        }
        return List.of(body);
    }

    private Map<String, Object> lastResponse(Source source) {
        Map<String, Object> response = responses.get(source.name());
        if (response == null) {
            throw new IllegalStateException(
                    "No request was sent to the HTTP service '" + source.name() + "' yet");
        }
        return response;
    }
}
