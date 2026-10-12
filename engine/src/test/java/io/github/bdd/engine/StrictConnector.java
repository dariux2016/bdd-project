package io.github.bdd.engine;

import io.github.bdd.plugin.Action;
import io.github.bdd.plugin.ConfigProperty;
import io.github.bdd.plugin.ConfigType;
import io.github.bdd.plugin.Connector;
import io.github.bdd.plugin.Phrase;

import java.util.List;

/** A connector without phrases, installed in tests for its demanding configuration schema. */
public final class StrictConnector implements Connector {

    @Override
    public String sourceType() {
        return "strict";
    }

    @Override
    public List<ConfigProperty> configSchema() {
        return List.of(
                new ConfigProperty("url", ConfigType.STRING, true),
                new ConfigProperty("secure", ConfigType.BOOLEAN, false));
    }

    @Override
    public List<Action> actions() {
        return List.of();
    }

    @Override
    public List<Phrase> defaultPhrases() {
        return List.of();
    }
}
