package io.github.bdd.plugin;

import java.util.Map;

/** A container a connector started for a source that declares an image. */
public interface LocalContainer extends AutoCloseable {

    /**
     * The configuration of the sources served by the container, e.g. its address. It is merged over
     * the {@code config} the source declares, so it takes precedence.
     */
    Map<String, Object> config();

    /** Stops and removes the container. */
    @Override
    void close();
}
