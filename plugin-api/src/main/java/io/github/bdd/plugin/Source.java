package io.github.bdd.plugin;

import java.util.Map;

/**
 * A source of the active profile, as cited by a step.
 *
 * @param name the logical name the features use for the source
 * @param config the connector parameters of the source, validated against the connector's
 *     {@link Connector#configSchema() schema} and converted to their declared type; optional
 *     properties that are not set are absent
 */
public record Source(String name, Map<String, Object> config) {

    public Source {
        config = Map.copyOf(config);
    }
}
