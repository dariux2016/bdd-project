package io.github.bdd.plugin;

import java.time.Instant;
import java.util.Map;

/** The values of an action's parameters for one step, already converted to their declared type. */
public final class Arguments {

    private final Map<String, Object> values;
    private final Instant scenarioStart;

    /** Arguments of a step whose scenario is not known: it is taken to have started at the epoch. */
    public Arguments(Map<String, Object> values) {
        this(values, Instant.EPOCH);
    }

    public Arguments(Map<String, Object> values, Instant scenarioStart) {
        this.values = Map.copyOf(values);
        this.scenarioStart = scenarioStart;
    }

    /**
     * When the scenario of the step started. A connector whose source keeps what happened before
     * the scenario, such as a message log, uses it to observe only what the scenario caused.
     */
    public Instant scenarioStart() {
        return scenarioStart;
    }

    public String string(String name) {
        return (String) value(name);
    }

    public int integer(String name) {
        return (Integer) value(name);
    }

    public Source source(String name) {
        return (Source) value(name);
    }

    private Object value(String name) {
        Object value = values.get(name);
        if (value == null) {
            throw new IllegalArgumentException("No argument named '" + name + "'");
        }
        return value;
    }
}
