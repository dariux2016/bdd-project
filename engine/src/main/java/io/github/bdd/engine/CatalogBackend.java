package io.github.bdd.engine;

import io.cucumber.core.backend.Backend;
import io.cucumber.core.backend.Glue;
import io.cucumber.core.backend.ParameterTypeDefinition;
import io.cucumber.cucumberexpressions.ParameterType;

import java.net.URI;
import java.util.List;

/** Cucumber backend whose step definitions come from the catalog instead of annotated code. */
final class CatalogBackend implements Backend {

    private final Catalog catalog;

    CatalogBackend(Catalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public void loadGlue(Glue glue, List<URI> gluePaths) {
        glue.addParameterType(new ParameterTypeDefinition() {
            @Override
            public ParameterType<?> parameterType() {
                return Catalog.integerOrVariable();
            }

            @Override
            public boolean isDefinedAt(StackTraceElement stackTraceElement) {
                return false;
            }

            @Override
            public String getLocation() {
                return "engine";
            }
        });
        catalog.definitions().forEach(glue::addStepDefinition);
    }
}
