package io.github.bdd.plugin;

import java.util.List;

/**
 * A plugin that lets the engine act on one type of source.
 *
 * <p>Implementations are discovered with {@link java.util.ServiceLoader} and instantiated once per
 * execution.
 */
public interface Connector {

    /** The type of source this connector handles, e.g. {@code postgresql}. */
    String sourceType();

    /** The parameters a source of this type accepts in the configuration; any other is rejected. */
    List<ConfigProperty> configSchema();

    /**
     * The actions of this connector. The engine refuses to run a {@link ActionKind#WRITE} action
     * when one of its {@link ParameterType#SOURCE} arguments is a read-only source, so handlers need
     * not check.
     */
    List<Action> actions();

    /** The phrases this connector contributes to the catalog, each targeting one of its actions. */
    List<Phrase> defaultPhrases();
}
