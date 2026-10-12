package io.github.bdd.engine;

/** The configuration of the sources cannot be used; raised before any scenario is executed. */
public final class ConfigurationException extends RuntimeException {

    ConfigurationException(String message) {
        super(message);
    }
}
