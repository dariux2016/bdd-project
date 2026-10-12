package io.github.bdd.plugin;

public enum ParameterType {
    /** Written between double quotes in a step; passed to the handler as a {@link String}. */
    STRING,
    /** Written as a bare number in a step; passed to the handler as an {@link Integer}. */
    INTEGER
}
