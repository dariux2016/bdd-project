package io.github.bdd.engine;

import io.cucumber.core.feature.FeatureParser;
import io.cucumber.core.gherkin.FeatureParserException;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.gherkin.Step;
import io.cucumber.core.resource.Resource;
import io.github.bdd.engine.FeatureValidation.StepValidation;
import io.github.bdd.engine.FeatureValidation.SyntaxError;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.options.RuntimeOptions;
import io.cucumber.core.options.RuntimeOptionsBuilder;
import io.cucumber.core.runtime.FeaturePathFeatureSupplier;
import io.cucumber.core.runtime.Runtime;
import io.github.bdd.plugin.Connector;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern PARSE_ERROR = Pattern.compile("\\((\\d+):\\d+\\):\\s*(.*)");
    private static final Pattern LANGUAGE_HEADER = Pattern.compile("^\\s*#\\s*language\\s*:\\s*([\\w-]+)\\s*$",
            Pattern.MULTILINE);

    /** The catalogs validation has loaded, until the {@code catalog.yaml} they came from changes. */
    private record Loaded(String stamp, Catalog catalog) {
    }

    private static final Map<Path, Loaded> VALIDATION_CATALOGS = new ConcurrentHashMap<>();

    /**
     * Matches the steps of the text of a feature against the effective catalog of a workspace, which
     * is what execution does: a step reported as matched is the one that would run.
     *
     * @throws ConfigurationException if the catalog file is invalid or two phrases can match the same step
     * @throws IllegalStateException if an installed connector declares an invalid phrase
     */
    public static FeatureValidation validate(Path workspace, String featureText) {
        Path root = workspace.toAbsolutePath().normalize();
        Catalog catalog = validationCatalog(root);
        String language = declaredLanguage(featureText);
        List<Pickle> pickles;
        try {
            Optional<Feature> feature = new FeatureParser(UUID::randomUUID).parseResource(new Resource() {
                @Override
                public URI getUri() {
                    return root.resolve("features/validation.feature").toUri();
                }

                @Override
                public InputStream getInputStream() {
                    return new ByteArrayInputStream(featureText.getBytes(StandardCharsets.UTF_8));
                }
            });
            pickles = feature.map(Feature::getPickles).orElse(List.of());
        } catch (FeatureParserException e) {
            return new FeatureValidation(language, syntaxErrors(e), List.of());
        }

        // A step of a Scenario Outline has a pickle for each example: the first that has a problem counts.
        Map<Integer, StepValidation> byLine = new TreeMap<>();
        for (Pickle pickle : pickles) {
            language = pickle.getLanguage();
            for (Step step : pickle.getSteps()) {
                StepValidation validated = validated(catalog, step);
                StepValidation known = byLine.get(step.getLine());
                if (known == null || (known.status().equals(FeatureValidation.MATCHED)
                        && !validated.status().equals(FeatureValidation.MATCHED))) {
                    byLine.put(step.getLine(), validated);
                }
            }
        }
        return new FeatureValidation(language, List.of(), new ArrayList<>(byLine.values()));
    }

    private static StepValidation validated(Catalog catalog, Step step) {
        String text = step.getText();
        List<CatalogEntry> matching = catalog.matching(text);
        if (matching.isEmpty()) {
            return new StepValidation(step.getLine(), text, FeatureValidation.UNDEFINED, null, null,
                    "No phrase of the catalog matches this step");
        }
        if (matching.size() > 1) {
            return new StepValidation(step.getLine(), text, FeatureValidation.AMBIGUOUS, null, null,
                    "More than one phrase matches this step: "
                            + String.join("; ", matching.stream().map(CatalogEntry::pattern).toList()));
        }
        CatalogEntry entry = matching.getFirst();
        return new StepValidation(step.getLine(), text, FeatureValidation.MATCHED, entry.pattern(),
                entry.origin(), null);
    }

    private static List<SyntaxError> syntaxErrors(FeatureParserException e) {
        List<SyntaxError> errors = new ArrayList<>();
        for (String line : String.valueOf(e.getMessage()).split("\\R")) {
            Matcher matcher = PARSE_ERROR.matcher(line.strip());
            if (matcher.matches()) {
                errors.add(new SyntaxError(Integer.parseInt(matcher.group(1)), matcher.group(2)));
            }
        }
        if (errors.isEmpty()) {
            errors.add(new SyntaxError(1, String.valueOf(e.getMessage())));
        }
        return errors;
    }

    private static String declaredLanguage(String featureText) {
        Matcher matcher = LANGUAGE_HEADER.matcher(featureText);
        return matcher.find() ? matcher.group(1) : "en";
    }

    private static Catalog validationCatalog(Path workspace) {
        Path file = workspace.resolve("catalog.yaml");
        String stamp;
        try {
            stamp = Files.isRegularFile(file)
                    ? Files.getLastModifiedTime(file).toMillis() + ":" + Files.size(file)
                    : "none";
        } catch (java.io.IOException e) {
            stamp = "unreadable";
        }
        Loaded loaded = VALIDATION_CATALOGS.get(workspace);
        if (loaded != null && loaded.stamp().equals(stamp)) {
            return loaded.catalog();
        }
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        List<Connector> connectors = ServiceLoader.load(Connector.class, classLoader).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        Catalog catalog = Catalog.load(workspace, connectors, Catalog.Context.NONE);
        VALIDATION_CATALOGS.put(workspace, new Loaded(stamp, catalog));
        return catalog;
    }

    /**
     * Executes the features of a workspace with the phrases of the installed connectors, on the
     * sources of a profile.
     *
     * @throws IllegalArgumentException if the workspace has no {@code features} directory, or the
     *     selection names a path that does not exist or an invalid tag expression
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
                .withAdditionalPlugins(collector, currentStep, variables)
                .build()
                .run();

        List<ScenarioResult> scenarios = collector.result().scenarios();
        reports.writeSummary(request.profile(), selection, start, Duration.between(start, Instant.now()), scenarios);
        reports.prune();
        return new ExecutionResult(scenarios, reports.directory());
    }
}
