package io.github.bdd.connector.fake;

import io.github.bdd.plugin.Action;
import io.github.bdd.plugin.ActionKind;
import io.github.bdd.plugin.Arguments;
import io.github.bdd.plugin.Connector;
import io.github.bdd.plugin.Parameter;
import io.github.bdd.plugin.ParameterType;
import io.github.bdd.plugin.Phrase;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** An in-memory store of integers by key, standing in for a real source in tests. */
public final class FakeConnector implements Connector {

    private static final Parameter KEY = new Parameter("key", ParameterType.STRING);

    private final Map<String, Integer> store = new HashMap<>();

    @Override
    public String sourceType() {
        return "fake";
    }

    @Override
    public List<Action> actions() {
        return List.of(
                new Action("put", ActionKind.WRITE,
                        List.of(KEY, new Parameter("value", ParameterType.INTEGER)), this::put),
                new Action("add", ActionKind.WRITE,
                        List.of(KEY, new Parameter("amount", ParameterType.INTEGER)), this::add),
                new Action("expect", ActionKind.READ,
                        List.of(KEY, new Parameter("value", ParameterType.INTEGER)), this::expect));
    }

    @Override
    public List<Phrase> defaultPhrases() {
        return List.of(
                new Phrase("the fake store has {key} set to {value}", "put"),
                new Phrase("{amount} is added to {key} in the fake store", "add"),
                new Phrase("the fake store should have {key} equal to {value}", "expect"));
    }

    private void put(Arguments arguments) {
        store.put(arguments.string("key"), arguments.integer("value"));
    }

    private void add(Arguments arguments) {
        store.merge(arguments.string("key"), arguments.integer("amount"), Integer::sum);
    }

    private void expect(Arguments arguments) {
        String key = arguments.string("key");
        int expected = arguments.integer("value");
        Integer actual = store.get(key);
        if (actual == null || actual != expected) {
            throw new AssertionError(
                    "Expected \"" + key + "\" to equal " + expected + " but was " + actual);
        }
    }
}
