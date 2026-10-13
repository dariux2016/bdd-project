package io.github.bdd.engine;

import io.cucumber.core.feature.FeatureWithLines;
import io.cucumber.core.options.RuntimeOptions;
import io.cucumber.core.options.RuntimeOptionsBuilder;
import io.cucumber.core.runtime.Runtime;
import io.github.bdd.plugin.Connector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;

/** The single entry point of the engine. */
public final class Engine {

    private Engine() {
    }

    /**
     * The effective catalog of a workspace: the default phrases of the installed connectors, those of
     * the engine, and the aliases of the workspace's {@code catalog.yaml}.
     *
     * @throws ConfigurationException if the catalog file is invalid, an alias does not fit the phrase
     *     it stands for, or two phrases can match the same step
     * @throws IllegalStateException if an installed connector declares an invalid phrase
     */
    public static List<CatalogEntry> catalog(Path workspace) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        List<Connector> connectors = ServiceLoader.load(Connector.class, classLoader).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        return Catalog.load(workspace.toAbsolutePath().normalize(), connectors, Catalog.Context.NONE).entries();
    }

    /**
     * Executes the features of a workspace with the phrases of the installed connectors, on the
     * sources of a profile.
     *
     * @throws IllegalArgumentException if the workspace has no {@code features} directory
     * @throws ConfigurationException if the configuration of the sources is invalid, lacks the
     *     requested profile or uses a source type no installed connector handles, or if the settings
     *     of the workspace are invalid
     * @throws IllegalStateException if an installed connector declares an invalid phrase
     */
    public static ExecutionResult run(ExecutionRequest request) {
        Path workspace = request.workspace().toAbsolutePath().normalize();
        Path features = workspace.resolve("features");
        if (!Files.isDirectory(features)) {
            throw new IllegalArgumentException("No 'features' directory in workspace " + workspace);
        }

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        List<Connector> connectors = ServiceLoader.load(Connector.class, classLoader).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        Profile profile = Profile.load(workspace, request.profile(), request.environment(), connectors);
        CurrentStep currentStep = new CurrentStep(workspace);
        Variables variables = new Variables(request.environment());
        Catalog catalog = Catalog.load(workspace, connectors, new Catalog.Context(
                profile, Polling.load(workspace), Diagnostics.load(workspace), currentStep, variables));
        CatalogBackend backend = new CatalogBackend(catalog);
        ResultCollector collector = new ResultCollector(workspace);

        RuntimeOptions options = new RuntimeOptionsBuilder()
                .addFeature(FeatureWithLines.create(features.toUri(), List.of()))
                .build();
        Runtime.builder()
                .withRuntimeOptions(options)
                .withClassLoader(() -> classLoader)
                .withBackendSupplier(() -> List.of(backend))
                .withAdditionalPlugins(collector, currentStep, variables)
                .build()
                .run();

        return collector.result();
    }
}
