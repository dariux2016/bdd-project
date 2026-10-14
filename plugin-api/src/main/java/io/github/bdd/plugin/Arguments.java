package io.github.bdd.plugin;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The values of an action's parameters for one step, already converted to their declared type. */
public final class Arguments {

    private final Map<String, Object> values;
    private final List<List<String>> table;
    private final String text;
    private final Instant scenarioStart;

    public Arguments(Map<String, Object> values) {
        this(values, null, null);
    }

    /**
     * @param table the rows of the step's data table, header row included, or {@code null}
     * @param text the content of the step's doc string, or {@code null}
     */
    public Arguments(Map<String, Object> values, List<List<String>> table, String text) {
        this(values, table, text, Instant.EPOCH);
    }

    private Arguments(Map<String, Object> values, List<List<String>> table, String text, Instant scenarioStart) {
        this.values = Map.copyOf(values);
        this.table = table;
        this.text = text;
        this.scenarioStart = scenarioStart;
    }

    /** Gives these arguments the instant their scenario started; the engine does it for every step. */
    public Arguments atScenarioStart(Instant scenarioStart) {
        return new Arguments(values, table, text, scenarioStart);
    }

    /**
     * When the scenario of the step started, or the epoch if it is not known. A connector whose
     * source keeps what happened before the scenario, such as a message log, uses it to observe
     * only what the scenario caused.
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

    public Document document(String name) {
        return (Document) value(name);
    }

    /** The rows of the data table of the step, header row included; cells are never null. */
    public List<List<String>> table() {
        if (table == null) {
            throw new IllegalArgumentException("This step needs a table");
        }
        return table;
    }

    /** The content of the doc string of the step. */
    public String text() {
        if (text == null) {
            throw new IllegalArgumentException("This step needs a doc string");
        }
        return text;
    }

    /** The rows of the data table of the step, if it has one. */
    public Optional<List<List<String>>> optionalTable() {
        return Optional.ofNullable(table);
    }

    /** The content of the doc string of the step, if it has one. */
    public Optional<String> optionalText() {
        return Optional.ofNullable(text);
    }

    private Object value(String name) {
        Object value = values.get(name);
        if (value == null) {
            throw new IllegalArgumentException("No argument named '" + name + "'");
        }
        return value;
    }
}
