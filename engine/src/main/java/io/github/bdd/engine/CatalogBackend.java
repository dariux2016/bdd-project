package io.github.bdd.engine;

import io.cucumber.core.backend.Backend;
import io.cucumber.core.backend.Glue;
import io.github.bdd.plugin.Action;
import io.github.bdd.plugin.Connector;
import io.github.bdd.plugin.Phrase;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Cucumber backend whose step definitions come from the catalog instead of annotated code. */
final class CatalogBackend implements Backend {

    private final List<PhraseStepDefinition> stepDefinitions = new ArrayList<>();

    CatalogBackend(List<Connector> connectors) {
        for (Connector connector : connectors) {
            for (Phrase phrase : connector.defaultPhrases()) {
                stepDefinitions.add(
                        new PhraseStepDefinition(connector.sourceType(), phrase, action(connector, phrase)));
            }
        }
    }

    private static Action action(Connector connector, Phrase phrase) {
        return connector.actions().stream()
                .filter(action -> action.name().equals(phrase.action()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Phrase '" + phrase.pattern() + "' of connector '"
                        + connector.sourceType() + "' targets unknown action '" + phrase.action() + "'"));
    }

    @Override
    public void loadGlue(Glue glue, List<URI> gluePaths) {
        stepDefinitions.forEach(glue::addStepDefinition);
    }
}
