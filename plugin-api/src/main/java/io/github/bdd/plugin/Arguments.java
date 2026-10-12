package io.github.bdd.plugin;

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

    private Object value(String name) {
        Object value = values.get(name);
        if (value == null) {
            throw new IllegalArgumentException("No argument named '" + name + "'");
        }
        return value;
    }
}
