package io.github.bdd.engine;

import io.github.bdd.plugin.Action;
import io.github.bdd.plugin.ActionKind;
import io.github.bdd.plugin.Arguments;
import io.github.bdd.plugin.ConfigProperty;
import io.github.bdd.plugin.Connector;
import io.github.bdd.plugin.Parameter;
import io.github.bdd.plugin.ParameterType;
import io.github.bdd.plugin.Phrase;

import java.time.Duration;
import java.util.List;

/**
 * The phrases every catalog has, whatever connectors are installed: they act on no source. They are
 * declared as those of a connector, but there are no sources of this type.
 */
final class BuiltInPhrases implements Connector {

    @Override
    public String sourceType() {
        return "engine";
    }

    @Override
    public List<ConfigProperty> configSchema() {
        return List.of();
    }

    @Override
    public List<Action> actions() {
        return List.of(new Action("wait", ActionKind.READ,
                List.of(new Parameter("seconds", ParameterType.INTEGER)), BuiltInPhrases::waitFor));
    }

    @Override
    public List<Phrase> defaultPhrases() {
        return List.of(
                new Phrase("I wait {seconds} second(s)", "wait"),
                new Phrase("attendo {seconds} secondo/secondi", "wait"));
    }

    /** For the rare cases the polling of the verifications does not cover. */
    private static List<?> waitFor(Arguments arguments) {
        int seconds = arguments.integer("seconds");
        if (seconds < 0) {
            throw new IllegalArgumentException("A wait cannot be negative, but was " + seconds + " seconds");
        }
        Polling.sleep(Duration.ofSeconds(seconds));
        return List.of();
    }
}
