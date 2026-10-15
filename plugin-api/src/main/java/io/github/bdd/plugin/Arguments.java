package io.github.bdd.plugin;

import java.util.List;
import java.util.Map;

/** The values of an action's parameters for one step, already converted to their declared type. */
public final class Arguments {

    private final Map<String, Object> values;

    public Arguments(Map<String, Object> values) {
        this.values = Map.copyOf(values);
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

    /** The rows of a {@link ParameterType#TABLE} argument, the first included. */
    @SuppressWarnings("unchecked")
    public List<List<String>> table(String name) {
        return (List<List<String>>) value(name);
    }

    /**
     * Whether the argument has a value, which only those of the {@link ParameterType#TEXT} and
     * {@link ParameterType#TABLE} parameters may lack.
     */
    public boolean has(String name) {
        return values.containsKey(name);
    }

    private Object value(String name) {
        Object value = values.get(name);
        if (value == null) {
            throw new IllegalArgumentException("No argument named '" + name + "'");
        }
        return value;
    }
}
