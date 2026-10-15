package io.github.bdd.plugin;

public enum ConfigType {
    /** Passed to the connector as a {@link String}. */
    STRING,
    /** Passed to the connector as an {@link Integer}. */
    INTEGER,
    /** Passed to the connector as a {@link Boolean}. */
    BOOLEAN,
    /**
     * A mapping of names to texts, passed to the connector as a {@code Map<String, String>}, e.g. the
     * default headers of a service. Each value may cite environment variables, like a {@link #STRING}.
     */
    MAP
}
