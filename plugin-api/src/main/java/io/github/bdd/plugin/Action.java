package io.github.bdd.plugin;

import java.util.List;

/**
 * A typed operation a connector can perform on its source.
 *
 * @param name unique within the connector
 * @param kind whether the action only reads the sources it is given or also modifies them
 * @param parameters the arguments the handler expects, by name
 * @param argument the table or doc string the steps of the action's phrases must have
 */
public record Action(String name, ActionKind kind, List<Parameter> parameters, ActionHandler handler,
        StepArgument argument) {

    public Action {
        parameters = List.copyOf(parameters);
        argument = argument == null ? StepArgument.NONE : argument;
    }

    /** An action whose steps have neither a table nor a doc string. */
    public Action(String name, ActionKind kind, List<Parameter> parameters, ActionHandler handler) {
        this(name, kind, parameters, handler, StepArgument.NONE);
    }
}
