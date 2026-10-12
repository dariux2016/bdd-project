package io.github.bdd.engine;

public enum Status {
    PASSED,
    FAILED,
    /** Not run because an earlier step of the scenario did not pass. */
    SKIPPED,
    /** No phrase of the catalog matches the step. */
    UNDEFINED
}
