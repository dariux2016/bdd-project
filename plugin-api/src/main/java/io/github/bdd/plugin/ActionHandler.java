package io.github.bdd.plugin;

@FunctionalInterface
public interface ActionHandler {

    /** Performs the action. Any exception makes the step fail with the exception's message. */
    void execute(Arguments arguments) throws Exception;
}
