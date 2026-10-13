package io.github.bdd.connector.fake;

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
import io.github.bdd.plugin.Phrase;
import io.github.bdd.plugin.Source;
import io.github.bdd.plugin.Verification;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory stores, one per source, standing in for real sources in tests. A store holds integers
 * by key and, separately, a list of JSON records. A record may be made to appear after a delay, as
 * the data of an asynchronous system does.
 */
public final class FakeConnector implements Connector {

    private static final Parameter SOURCE = new Parameter("source", ParameterType.SOURCE);
    private static final Parameter KEY = new Parameter("key", ParameterType.STRING);

    /** The value of the keys that were never set; without it they have no value. */
    private static final String DEFAULT = "default";

    private final Map<String, Map<String, Integer>> stores = new HashMap<>();
    private final Map<String, List<StoredRecord>> records = new HashMap<>();
    private final ObjectMapper json = new ObjectMapper();

    /** @param visibleAt the {@link System#nanoTime()} from which the store shows the record */
    private record StoredRecord(Object json, long visibleAt) {
    }

    @Override
    public String sourceType() {
        return "fake";
    }

    @Override
    public List<ConfigProperty> configSchema() {
        return List.of(new ConfigProperty(DEFAULT, ConfigType.INTEGER, false));
    }

    @Override
    public List<Action> actions() {
        return List.of(
                new Action("put", ActionKind.WRITE,
                        List.of(SOURCE, KEY, new Parameter("value", ParameterType.INTEGER)), this::put),
                new Action("add", ActionKind.WRITE,
                        List.of(SOURCE, KEY, new Parameter("amount", ParameterType.INTEGER)), this::add),
                new Action("expect", ActionKind.READ,
                        List.of(SOURCE, KEY, new Parameter("value", ParameterType.INTEGER)), this::expect),
                new Action("insert", ActionKind.WRITE,
                        List.of(SOURCE, new Parameter("json", ParameterType.STRING)), this::insert),
                new Action("insertLater", ActionKind.WRITE,
                        List.of(SOURCE, new Parameter("json", ParameterType.STRING),
                                new Parameter("delay", ParameterType.INTEGER)), this::insertLater),
                new Action("records", ActionKind.READ, List.of(SOURCE), this::records));
    }

    @Override
    public List<Phrase> defaultPhrases() {
        List<Phrase> phrases = new ArrayList<>(english());
        phrases.addAll(italian());
        return phrases;
    }

    private static List<Phrase> english() {
        return List.of(
                new Phrase("the fake store {source} has {key} set to {value}", "put"),
                new Phrase("{amount} is added to {key} in the fake store {source}", "add"),
                new Phrase("the fake store {source} should have {key} equal to {value}", "expect"),
                new Phrase("the fake store {source} holds the record {json}", "insert"),
                new Phrase("the fake store {source} will hold the record {json} after {delay} ms", "insertLater"),
                new Phrase("a record exists in the fake store {source}", "records", Verification.EXISTS),
                new Phrase("a record exists in the fake store {source} within {timeout} second(s)", "records",
                        Verification.EXISTS),
                new Phrase("no record exists in the fake store {source}", "records", Verification.NOT_EXISTS),
                new Phrase("no record exists in the fake store {source} for {timeout} second(s)", "records",
                        Verification.NOT_EXISTS),
                new Phrase("exactly {count} record(s) exist(s) in the fake store {source}", "records",
                        Verification.COUNT_EXACTLY),
                new Phrase("at least {count} record(s) exist(s) in the fake store {source}", "records",
                        Verification.COUNT_AT_LEAST),
                new Phrase("at most {count} record(s) exist(s) in the fake store {source}", "records",
                        Verification.COUNT_AT_MOST),
                new Phrase("a record of the fake store {source} equals the JSON", "records",
                        Verification.JSON_STRICT),
                new Phrase("a record of the fake store {source} equals the JSON ignoring {ignored}", "records",
                        Verification.JSON_STRICT),
                new Phrase("a record of the fake store {source} contains the JSON", "records",
                        Verification.JSON_PARTIAL),
                new Phrase("a record of the fake store {source} contains the JSON ignoring {ignored}", "records",
                        Verification.JSON_PARTIAL),
                new Phrase("I save the {field} of a record of the fake store {source} as {variable}", "records",
                        Verification.SAVE),
                new Phrase("I save the {field} of a record of the fake store {source} as {variable} within "
                        + "{timeout} second(s)", "records", Verification.SAVE));
    }

    private static List<Phrase> italian() {
        return List.of(
                italian("lo store finto {source} ha {key} impostato a {value}", "put", null),
                italian("{amount} viene aggiunto a {key} nello store finto {source}", "add", null),
                italian("lo store finto {source} deve avere {key} uguale a {value}", "expect", null),
                italian("lo store finto {source} contiene il record {json}", "insert", null),
                italian("lo store finto {source} conterrà il record {json} dopo {delay} ms", "insertLater", null),
                italian("esiste un record nello store finto {source}", "records", Verification.EXISTS),
                italian("esiste un record nello store finto {source} entro {timeout} secondo/secondi", "records",
                        Verification.EXISTS),
                italian("non esiste alcun record nello store finto {source}", "records", Verification.NOT_EXISTS),
                italian("non esiste alcun record nello store finto {source} per {timeout} secondo/secondi",
                        "records", Verification.NOT_EXISTS),
                italian("esistono esattamente {count} record nello store finto {source}", "records",
                        Verification.COUNT_EXACTLY),
                italian("esistono almeno {count} record nello store finto {source}", "records",
                        Verification.COUNT_AT_LEAST),
                italian("esistono al massimo {count} record nello store finto {source}", "records",
                        Verification.COUNT_AT_MOST),
                italian("un record dello store finto {source} è uguale al JSON", "records",
                        Verification.JSON_STRICT),
                italian("un record dello store finto {source} è uguale al JSON ignorando {ignored}", "records",
                        Verification.JSON_STRICT),
                italian("un record dello store finto {source} contiene il JSON", "records",
                        Verification.JSON_PARTIAL),
                italian("un record dello store finto {source} contiene il JSON ignorando {ignored}", "records",
                        Verification.JSON_PARTIAL),
                italian("salvo {field} di un record dello store finto {source} come {variable}", "records",
                        Verification.SAVE),
                italian("salvo {field} di un record dello store finto {source} come {variable} entro "
                        + "{timeout} secondo/secondi", "records", Verification.SAVE));
    }

    private static Phrase italian(String pattern, String action, Verification verification) {
        return new Phrase(pattern, action, verification, Phrase.ITALIAN);
    }

    private List<?> put(Arguments arguments) {
        store(arguments.source("source")).put(arguments.string("key"), arguments.integer("value"));
        return List.of();
    }

    private List<?> add(Arguments arguments) {
        Source source = arguments.source("source");
        String key = arguments.string("key");
        Integer current = read(source, key);
        store(source).put(key, (current == null ? 0 : current) + arguments.integer("amount"));
        return List.of();
    }

    private List<?> expect(Arguments arguments) {
        String key = arguments.string("key");
        int expected = arguments.integer("value");
        Integer actual = read(arguments.source("source"), key);
        if (actual == null || actual != expected) {
            throw new AssertionError(
                    "Expected \"" + key + "\" to equal " + expected + " but was " + actual);
        }
        return List.of();
    }

    private List<?> insert(Arguments arguments) throws JsonProcessingException {
        return insert(arguments, 0);
    }

    private List<?> insertLater(Arguments arguments) throws JsonProcessingException {
        return insert(arguments, arguments.integer("delay"));
    }

    private List<?> insert(Arguments arguments, int delayMillis) throws JsonProcessingException {
        stored(arguments).add(new StoredRecord(json.readValue(arguments.string("json"), Object.class),
                System.nanoTime() + delayMillis * 1_000_000L));
        return List.of();
    }

    private List<?> records(Arguments arguments) {
        long now = System.nanoTime();
        return stored(arguments).stream()
                .filter(record -> record.visibleAt() - now <= 0)
                .map(StoredRecord::json)
                .toList();
    }

    private List<StoredRecord> stored(Arguments arguments) {
        return records.computeIfAbsent(arguments.source("source").name(), name -> new ArrayList<>());
    }

    private Integer read(Source source, String key) {
        Integer value = store(source).get(key);
        return value != null ? value : (Integer) source.config().get(DEFAULT);
    }

    private Map<String, Integer> store(Source source) {
        return stores.computeIfAbsent(source.name(), name -> new HashMap<>());
    }
}
