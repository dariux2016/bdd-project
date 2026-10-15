package io.github.bdd.plugin;

public enum ParameterType {
    /** Written between double quotes in a step; passed to the handler as a {@link String}. */
    STRING,
    /** Written as a bare number in a step; passed to the handler as an {@link Integer}. */
    INTEGER,
    /**
     * The logical name of a source, written between double quotes in a step; passed to the handler
     * as the {@link Source} of that name in the active profile, which is of the connector's type.
     */
    SOURCE,
    /**
     * The doc string of the step, which the pattern does not mention; passed to the handler as a
     * {@link String}, or absent when the step has none. A phrase with a verification cannot target
     * an action that has one, since the verification takes the doc string of the step.
     */
    TEXT,
    /**
     * The table of the step, which the pattern does not mention; passed to the handler as a
     * {@code List<List<String>>} of its rows, the first included, or absent when the step has none.
     * A phrase with a verification cannot target an action that has one, since the verification
     * takes the table of the step.
     */
    TABLE
}
