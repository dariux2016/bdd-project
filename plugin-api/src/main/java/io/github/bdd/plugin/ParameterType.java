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
     * The path, relative to the workspace, of a text file, written between double quotes in a step;
     * passed to the handler as the {@link String} content of the file, read as UTF-8.
     */
    FILE,
    /**
     * A multiline text, given as the doc string of the step instead of being written in the phrase:
     * the pattern of a phrase does not mention it, and every step of the phrase must have the doc
     * string. Passed to the handler as a {@link String}. Only {@link ActionKind#WRITE} actions may
     * have one, since the doc string of a verification is the expected JSON.
     */
    TEXT
}
