package io.github.bdd.engine;

/**
 * Told how an execution advances, on the thread that runs it. A listener must not block: the
 * execution waits for it.
 */
public interface ExecutionListener {

    /** The features were read and the execution begins. */
    default void started() {
    }

    /** @param feature path of the feature file relative to the workspace, with forward slashes */
    default void featureStarted(String feature) {
    }

    default void scenarioStarted(String feature, int line, String name) {
    }

    default void stepStarted(String keyword, String text) {
    }

    default void stepFinished(StepResult step) {
    }

    default void scenarioFinished(ScenarioResult scenario) {
    }

    /** @param status {@link Status#PASSED} if every scenario of the feature passed, else {@link Status#FAILED} */
    default void featureFinished(String feature, Status status) {
    }
}
