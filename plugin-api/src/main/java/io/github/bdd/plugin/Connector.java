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

    List<Action> actions();

    /** The phrases this connector contributes to the catalog, each targeting one of its actions. */
    List<Phrase> defaultPhrases();
}
