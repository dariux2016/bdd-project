package io.github.bdd.engine;

public enum Status {
    PASSED,
    FAILED,
    /**
     * Its phrase is pending: the catalog has it, but no installed connector implements it yet. A
     * scenario is pending when a step is, and the steps after it are skipped.
     */
    PENDING,
    /** Not run because an earlier step of the scenario did not pass. */
    SKIPPED,
    /** No phrase of the catalog matches the step. */
    UNDEFINED
}
