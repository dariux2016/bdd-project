package io.github.bdd.engine;

import io.github.bdd.plugin.Connector;
import io.github.bdd.plugin.LocalContainer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The containers started for the sources that declare an image. An execution that is not given a
 * pool uses its own and destroys it when it ends; a long-lived caller, like the server, passes one
 * through {@link ExecutionRequest#containers()} to keep the containers active between executions
 * and closes it when it stops.
 */
public final class LocalContainers implements AutoCloseable {

    private final Map<String, LocalContainer> started = new LinkedHashMap<>();

    /**
     * The container of a connector's type from an image, started on first use.
     *
     * @throws UnsupportedOperationException if the connector cannot start containers
     */
    synchronized LocalContainer start(Connector connector, String image) {
        String key = connector.sourceType() + "@" + image;
        LocalContainer container = started.get(key);
        if (container == null) {
            container = connector.startContainer(image);
            started.put(key, container);
        }
        return container;
    }

    /** Stops every container this pool started, whether or not one of them fails to stop. */
    @Override
    public synchronized void close() {
        List<RuntimeException> failures = new ArrayList<>();
        for (LocalContainer container : started.values()) {
            try {
                container.close();
            } catch (RuntimeException e) {
                failures.add(e);
            }
        }
        started.clear();
        if (!failures.isEmpty()) {
            RuntimeException first = failures.getFirst();
            failures.stream().skip(1).forEach(first::addSuppressed);
            throw first;
        }
    }
}
