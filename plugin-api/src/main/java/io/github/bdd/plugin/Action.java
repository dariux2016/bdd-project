package io.github.bdd.plugin;

import java.util.List;

/**
 * A typed operation a connector can perform on its source.
 *
 * @param name unique within the connector
 * @param kind whether the action only reads the sources it is given or also modifies them
 * @param parameters the arguments the handler expects, by name
 * @param snapshot whether the action returns what was already observed, such as the last response
 *     of a service, rather than reading the source anew: calling it again cannot change the
 *     records, so the engine verifies them once instead of retrying until the timeout
 */
public record Action(String name, ActionKind kind, List<Parameter> parameters, ActionHandler handler,
        boolean snapshot) {

    public Action {
        parameters = List.copyOf(parameters);
    }

    /** An action that reads or acts on the source each time it is performed. */
    public Action(String name, ActionKind kind, List<Parameter> parameters, ActionHandler handler) {
        this(name, kind, parameters, handler, false);
    }
}
