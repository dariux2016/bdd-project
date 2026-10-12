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
    SOURCE
}
