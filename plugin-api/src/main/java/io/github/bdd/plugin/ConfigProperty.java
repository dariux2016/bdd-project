package io.github.bdd.plugin;

/**
 * A parameter a connector accepts in the configuration of a source.
 *
 * @param required whether a source of this connector's type must set it
 */
public record ConfigProperty(String name, ConfigType type, boolean required) {
}
