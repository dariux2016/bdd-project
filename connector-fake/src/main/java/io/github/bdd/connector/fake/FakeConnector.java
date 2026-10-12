package io.github.bdd.connector.fake;

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory stores of integers by key, one per source, standing in for real sources in tests. */
public final class FakeConnector implements Connector {

    private static final Parameter SOURCE = new Parameter("source", ParameterType.SOURCE);
    private static final Parameter KEY = new Parameter("key", ParameterType.STRING);

    /** The value of the keys that were never set; without it they have no value. */
    private static final String DEFAULT = "default";

    private final Map<String, Map<String, Integer>> stores = new HashMap<>();

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
                        List.of(SOURCE, KEY, new Parameter("value", ParameterType.INTEGER)), this::expect));
    }

    @Override
    public List<Phrase> defaultPhrases() {
        return List.of(
                new Phrase("the fake store {source} has {key} set to {value}", "put"),
                new Phrase("{amount} is added to {key} in the fake store {source}", "add"),
                new Phrase("the fake store {source} should have {key} equal to {value}", "expect"));
    }

    private void put(Arguments arguments) {
        store(arguments.source("source")).put(arguments.string("key"), arguments.integer("value"));
    }

    private void add(Arguments arguments) {
        Source source = arguments.source("source");
        String key = arguments.string("key");
        Integer current = read(source, key);
        store(source).put(key, (current == null ? 0 : current) + arguments.integer("amount"));
    }

    private void expect(Arguments arguments) {
        String key = arguments.string("key");
        int expected = arguments.integer("value");
        Integer actual = read(arguments.source("source"), key);
        if (actual == null || actual != expected) {
            throw new AssertionError(
                    "Expected \"" + key + "\" to equal " + expected + " but was " + actual);
        }
    }

    private Integer read(Source source, String key) {
        Integer value = store(source).get(key);
        return value != null ? value : (Integer) source.config().get(DEFAULT);
    }

    private Map<String, Integer> store(Source source) {
        return stores.computeIfAbsent(source.name(), name -> new HashMap<>());
    }
}
