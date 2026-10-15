package io.github.bdd.engine;

import io.cucumber.core.feature.FeatureParser;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.options.RuntimeOptions;
import io.cucumber.core.options.RuntimeOptionsBuilder;
import io.cucumber.core.runtime.FeaturePathFeatureSupplier;
import io.cucumber.core.runtime.Runtime;
import io.cucumber.plugin.EventListener;
import io.cucumber.plugin.event.TestCaseStarted;
import io.github.bdd.plugin.Connector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The single entry point of the engine. */
public final class Engine {

    private Engine() {
    }

    /**
     * The effective catalog of a workspace: the default phrases of the installed connectors, those of
     * the engine, and the aliases, composite and pending phrases of the workspace's
     * {@code catalog.yaml}. The installed connectors are those of the tool and those of the plugins
     * in the workspace's {@code plugins} folder.
     *
     * @throws ConfigurationException if a plugin cannot be loaded, two connectors handle the same
     *     source type, the catalog file is invalid, an alias does not fit the phrase it stands for,
     *     or two phrases can match the same step
     * @throws IllegalStateException if an installed connector declares an invalid phrase
     */
    public static List<CatalogEntry> catalog(Path workspace) {
        Path root = workspace.toAbsolutePath().normalize();
        try (Connectors installed = Connectors.load(root, Thread.currentThread().getContextClassLoader())) {
            return Catalog.load(root, installed.list(), Catalog.Context.NONE).entries();
        }
    }

    /**
     * Executes the features of a workspace with the phrases of the installed connectors, on the
     * sources of a profile.
     *
     * @throws IllegalArgumentException if the workspace has no {@code features} directory, or the
     *     selection names a path that does not exist or an invalid tag expression
     * @throws ConfigurationException if a plugin of the workspace cannot be loaded, two connectors
     *     handle the same source type, the configuration of the sources is invalid, lacks the
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
        try (Connectors installed = Connectors.load(workspace, classLoader)) {
            return run(request, workspace, installed.list(), classLoader);
        }
    }

    private static ExecutionResult run(ExecutionRequest request, Path workspace, List<Connector> connectors,
            ClassLoader classLoader) {
        Profile profile = Profile.load(workspace, request.profile(), request.environment(), connectors);
        CurrentStep currentStep = new CurrentStep(workspace);
        Variables variables = new Variables(request.environment());
        Catalog catalog = Catalog.load(workspace, connectors, new Catalog.Context(
                profile, Polling.load(workspace), Diagnostics.load(workspace), currentStep, variables));
        CatalogBackend backend = new CatalogBackend(catalog);
        ResultCollector collector = new ResultCollector(workspace, currentStep);
        EventListener scenarios = publisher -> publisher.registerHandlerFor(
                TestCaseStarted.class, event -> connectors.forEach(Connector::scenarioStarted));

        Selection selection = request.selection();
        RuntimeOptionsBuilder builder = new RuntimeOptionsBuilder();
        selection.features(workspace).forEach(builder::addFeature);
        if (selection.expression() != null) {
            builder.addTagFilter(selection.expression());
        }
        // Parsed before the report files are opened, so that an unparsable feature leaves none behind.
        List<Feature> parsed = new FeaturePathFeatureSupplier(
                () -> classLoader, builder.build(), new FeatureParser(UUID::randomUUID)).get();
        Instant start = Instant.now();
        Reports reports = Reports.create(workspace, start);
        reports.pluginNames().forEach(builder::addPluginName);
        RuntimeOptions options = builder.build();
        Runtime.builder()
                .withRuntimeOptions(options)
                .withClassLoader(() -> classLoader)
                .withFeatureSupplier(() -> parsed)
                .withBackendSupplier(() -> List.of(backend))
                .withAdditionalPlugins(collector, currentStep, variables, scenarios)
                .build()
                .run();

        ExecutionResult result = collector.result();
        // Unless pending scenarios fail the execution, JUnit XML must not count them as failures.
        if (!request.failOnPending()
                && result.scenarios().stream().anyMatch(scenario -> scenario.status() == Status.PENDING)) {
            reports.reportPendingAsSkipped();
        }
        reports.writeSummary(request.profile(), selection, start, Duration.between(start, Instant.now()),
                result.scenarios(), result.successful(request.failOnPending()));
        reports.prune();
        return new ExecutionResult(result.scenarios(), reports.directory());
    }
}
