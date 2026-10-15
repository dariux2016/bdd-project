package io.github.bdd.connector.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bdd.connector.http.Service.Response;
import io.github.bdd.plugin.Action;
import io.github.bdd.plugin.ActionKind;
import io.github.bdd.plugin.Arguments;
import io.github.bdd.plugin.ConfigProperty;
import io.github.bdd.plugin.ConfigType;
import io.github.bdd.plugin.Connector;
import io.github.bdd.plugin.Parameter;
import io.github.bdd.plugin.ParameterType;
import io.github.bdd.plugin.Phrase;
import io.github.bdd.plugin.Source;
import io.github.bdd.plugin.Verification;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * HTTP services, one per source: the steps send requests to a service and verify its last response.
 *
 * <p>The last response, and the headers a scenario adds to every request, belong to the scenario:
 * they are forgotten when the next one starts.
 */
public final class HttpConnector implements Connector {

    /** The methods that only read, which a read-only source accepts; the others may modify data. */
    private static final List<String> READING = List.of("GET", "HEAD", "OPTIONS");
    private static final List<String> WRITING = List.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> TABLE_TITLES = Set.of("header|value", "header|valore");

    private static final Parameter SOURCE = new Parameter("source", ParameterType.SOURCE);
    private static final Parameter PATH = new Parameter("path", ParameterType.STRING);
    private static final Parameter BODY = new Parameter("body", ParameterType.TEXT);
    private static final Parameter HEADERS = new Parameter("headers", ParameterType.TABLE);
    private static final Parameter STATUS = new Parameter("status", ParameterType.INTEGER);

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Service> services = new HashMap<>();
    /** By source name, for the scenario being executed. */
    private final Map<String, Response> lastResponses = new HashMap<>();
    private final Map<String, Map<String, String>> scenarioHeaders = new HashMap<>();

    @Override
    public String sourceType() {
        return "http";
    }

    @Override
    public List<ConfigProperty> configSchema() {
        return List.of(
                new ConfigProperty(Service.URL, ConfigType.STRING, true),
                new ConfigProperty(Service.HEADERS, ConfigType.MAP, false),
                new ConfigProperty(Service.USERNAME, ConfigType.STRING, false),
                new ConfigProperty(Service.PASSWORD, ConfigType.STRING, false),
                new ConfigProperty(Service.CONNECT_TIMEOUT, ConfigType.INTEGER, false),
                new ConfigProperty(Service.RESPONSE_TIMEOUT, ConfigType.INTEGER, false));
    }

    @Override
    public void scenarioStarted() {
        lastResponses.clear();
        scenarioHeaders.clear();
    }

    @Override
    public List<Action> actions() {
        List<Action> actions = new ArrayList<>();
        for (String method : READING) {
            actions.add(new Action(action(method), ActionKind.READ, List.of(SOURCE, PATH, BODY, HEADERS),
                    arguments -> send(method, arguments)));
        }
        for (String method : WRITING) {
            actions.add(new Action(action(method), ActionKind.WRITE, List.of(SOURCE, PATH, BODY, HEADERS),
                    arguments -> send(method, arguments)));
        }
        actions.add(new Action("addHeaders", ActionKind.READ, List.of(SOURCE, HEADERS), this::addHeaders));
        actions.add(new Action("poll", ActionKind.READ, List.of(SOURCE, PATH), this::poll));
        actions.add(new Action("response", ActionKind.READ, List.of(SOURCE),
                arguments -> List.of(last(arguments).record()), true));
        actions.add(new Action("responseBody", ActionKind.READ, List.of(SOURCE),
                arguments -> last(arguments).bodyRecord(), true));
        actions.add(new Action("status", ActionKind.READ, List.of(SOURCE, STATUS), this::status, true));
        return actions;
    }

    private static String action(String method) {
        return method.toLowerCase(Locale.ROOT);
    }

    @Override
    public List<Phrase> defaultPhrases() {
        List<Phrase> phrases = new ArrayList<>();
        for (String method : concat(READING, WRITING)) {
            String article = method.equals("OPTIONS") ? "an" : "a";
            phrases.add(new Phrase("I send " + article + " " + method + " request to {path} on the HTTP service {source}",
                    action(method)));
            phrases.add(italian("invio una richiesta " + method + " a {path} sul servizio HTTP {source}",
                    action(method), null));
        }
        phrases.addAll(List.of(
                new Phrase("the requests to the HTTP service {source} have the headers", "addHeaders"),
                italian("le richieste al servizio HTTP {source} hanno gli header", "addHeaders", null),

                new Phrase("I send GET requests to {path} on the HTTP service {source} until the response matches",
                        "poll", Verification.EXISTS),
                new Phrase("I send GET requests to {path} on the HTTP service {source} until the response matches "
                        + "within {timeout} second(s)", "poll", Verification.EXISTS),
                italian("invio richieste GET a {path} sul servizio HTTP {source} finché la risposta corrisponde",
                        "poll", Verification.EXISTS),
                italian("invio richieste GET a {path} sul servizio HTTP {source} finché la risposta corrisponde "
                        + "entro {timeout} secondo/secondi", "poll", Verification.EXISTS),

                new Phrase("the response of the HTTP service {source} has status {status}", "status"),
                italian("la risposta del servizio HTTP {source} ha lo status {status}", "status", null),

                new Phrase("the response of the HTTP service {source} matches", "response", Verification.EXISTS),
                italian("la risposta del servizio HTTP {source} corrisponde", "response", Verification.EXISTS),

                new Phrase("the response body of the HTTP service {source} equals the JSON", "responseBody",
                        Verification.JSON_STRICT),
                new Phrase("the response body of the HTTP service {source} equals the JSON ignoring {ignored}",
                        "responseBody", Verification.JSON_STRICT),
                new Phrase("the response body of the HTTP service {source} contains the JSON", "responseBody",
                        Verification.JSON_PARTIAL),
                new Phrase("the response body of the HTTP service {source} contains the JSON ignoring {ignored}",
                        "responseBody", Verification.JSON_PARTIAL),
                italian("il corpo della risposta del servizio HTTP {source} è uguale al JSON", "responseBody",
                        Verification.JSON_STRICT),
                italian("il corpo della risposta del servizio HTTP {source} è uguale al JSON ignorando {ignored}",
                        "responseBody", Verification.JSON_STRICT),
                italian("il corpo della risposta del servizio HTTP {source} contiene il JSON", "responseBody",
                        Verification.JSON_PARTIAL),
                italian("il corpo della risposta del servizio HTTP {source} contiene il JSON ignorando {ignored}",
                        "responseBody", Verification.JSON_PARTIAL),

                new Phrase("I save the {field} of the response of the HTTP service {source} as {variable}",
                        "response", Verification.SAVE),
                italian("salvo {field} della risposta del servizio HTTP {source} come {variable}", "response",
                        Verification.SAVE)));
        return phrases;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static Phrase italian(String pattern, String action, Verification verification) {
        return new Phrase(pattern, action, verification, Phrase.ITALIAN);
    }

    private List<?> send(String method, Arguments arguments) throws IOException {
        Source source = arguments.source(SOURCE.name());
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(scenarioHeaders.getOrDefault(source.name(), Map.of()));
        if (arguments.has(HEADERS.name())) {
            headers.putAll(headers(arguments.table(HEADERS.name())));
        }
        String body = arguments.has(BODY.name()) ? arguments.string(BODY.name()) : null;
        // Forgotten first, so that a failed request leaves no response to verify.
        lastResponses.remove(source.name());
        Response response = service(source).send(method, arguments.string(PATH.name()), headers, body);
        lastResponses.put(source.name(), response);
        return List.of();
    }

    private List<?> poll(Arguments arguments) throws IOException {
        send("GET", arguments);
        return List.of(lastResponses.get(arguments.source(SOURCE.name()).name()).record());
    }

    private List<?> addHeaders(Arguments arguments) {
        if (!arguments.has(HEADERS.name())) {
            throw new IllegalArgumentException("This step needs the headers as a table");
        }
        scenarioHeaders.computeIfAbsent(arguments.source(SOURCE.name()).name(),
                        name -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER))
                .putAll(headers(arguments.table(HEADERS.name())));
        return List.of();
    }

    /** The headers of a table of <code>header | value</code> rows, after the row of titles. */
    private static Map<String, String> headers(List<List<String>> table) {
        List<String> titles = table.getFirst();
        if (!TABLE_TITLES.contains(String.join("|", titles.stream().map(HttpConnector::cell).toList())
                .toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("The table of headers must start with the row | header | value |, "
                    + "but starts with | " + String.join(" | ", titles.stream().map(HttpConnector::cell).toList())
                    + " |");
        }
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (List<String> row : table.subList(1, table.size())) {
            if (cell(row.getFirst()).isEmpty()) {
                throw new IllegalArgumentException("A row of the table of headers has no header name");
            }
            headers.put(cell(row.getFirst()), cell(row.get(1)));
        }
        return headers;
    }

    /** A cell of a table, which Cucumber gives as {@code null} when it is empty. */
    private static String cell(String text) {
        return text == null ? "" : text.strip();
    }

    private List<?> status(Arguments arguments) {
        Source source = arguments.source(SOURCE.name());
        int expected = arguments.integer(STATUS.name());
        int actual = last(arguments).status();
        if (actual != expected) {
            throw new AssertionError("Expected the response of the HTTP service '" + source.name()
                    + "' to have status " + expected + ", but it has " + actual);
        }
        return List.of();
    }

    private Response last(Arguments arguments) {
        String name = arguments.source(SOURCE.name()).name();
        Response response = lastResponses.get(name);
        if (response == null) {
            throw new IllegalStateException("No request was sent to the HTTP service '" + name + "' in this scenario");
        }
        return response;
    }

    private Service service(Source source) {
        Service service = services.get(source.name());
        if (service == null) {
            service = new Service(source, json);
            services.put(source.name(), service);
        }
        return service;
    }
}
