package io.github.bdd.engine;

public enum Status {
    PASSED,
    FAILED,
    /** Not run because an earlier step of the scenario did not pass. */
    SKIPPED,
    /** No phrase of the catalog matches the step. */
    UNDEFINED,
    /** The phrase of the step has no implementation yet: not a failure, but nothing was verified. */
    PENDING
}
