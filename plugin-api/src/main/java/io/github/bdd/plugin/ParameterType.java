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
     * The table of the step, which is not written in the phrase: the step gives it below its text.
     * Passed to the handler as the rows of the table, header row included. An action has at most one,
     * and the step may do without it, as for a {@link #TEXT} parameter, which shares the position.
     */
    TABLE,
    /**
     * The doc string of the step, which is not written in the phrase: the step gives it below its
     * text. Passed to the handler as the text between the delimiters. An action has at most one,
     * and the step may do without it.
     */
    TEXT
}
