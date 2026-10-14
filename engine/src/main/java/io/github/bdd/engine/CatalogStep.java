package io.github.bdd.engine;

import io.cucumber.core.backend.StepDefinition;
import io.github.bdd.plugin.Parameter;

import java.util.List;

/** What the catalog holds for a phrase: a step definition that also tells the parameters of the phrase. */
interface CatalogStep extends StepDefinition {

    /** The parameters of the phrase, in the order the pattern uses them. */
    List<Parameter> parameters();
}
