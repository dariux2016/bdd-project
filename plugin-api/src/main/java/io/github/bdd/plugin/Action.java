package io.github.bdd.plugin;

import java.util.List;

/**
 * A typed operation a connector can perform on its source.
 *
 * @param name unique within the connector
 * @param kind whether the action only reads the sources it is given or also modifies them
 * @param parameters the arguments the handler expects, by name
 */
public record Action(String name, ActionKind kind, List<Parameter> parameters, ActionHandler handler) {

    public Action {
        parameters = List.copyOf(parameters);
    }
}
