package io.github.bdd.engine;

import io.cucumber.core.backend.StepDefinition;
import io.cucumber.cucumberexpressions.ExpressionFactory;
import io.cucumber.cucumberexpressions.ParameterType;
import io.cucumber.cucumberexpressions.ParameterTypeRegistry;
import io.github.bdd.plugin.Action;
import io.github.bdd.plugin.Connector;
import io.github.bdd.plugin.Phrase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The effective catalog: the default phrases of the connectors, those every catalog has, and the
 * aliases the workspace adds in its {@code catalog.yaml}.
 *
 * <pre>
 * aliases:
 *   - phrase: "il negozio {source} contiene {key} = {value}"   # the new wording
 *     for: "the fake store {source} has {key} set to {value}"  # a default phrase it stands for
 *     language: it                                             # optional, en by default
 *
 * pending:
 *   - id: reports.rebuild                        # identifies the phrase, e.g. in its phrase request
 *     phrase: "the report {name} is rebuilt"     # each parameter takes a quoted text
 *     language: en                               # optional, en by default
 * </pre>
 *
 * A pending phrase has no implementation: its steps are {@code pending}, until a connector
 * declares a phrase with the same {@link Phrase#id() id}.
 */
final class Catalog {

    /** The parameter type of integers, which a step may also give as a variable. */
    static final String INTEGER_OR_VARIABLE = "intvar";

    private static final YamlFile YAML = new YamlFile("catalog.yaml");
    private static final Set<String> LANGUAGES = Set.of(Phrase.ENGLISH, Phrase.ITALIAN);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
    private static final Pattern OPTIONAL = Pattern.compile("\\(([^()]*)\\)");
    private static final Pattern ALTERNATION = Pattern.compile("[^\\s/{}()]+(?:/[^\\s/{}()]+)+");

    /** What the step definitions need to run, which describing the catalog does not. */
    record Context(Profile profile, Polling polling, Diagnostics diagnostics, CurrentStep currentStep,
            Variables variables) {

        static final Context NONE = new Context(null, null, null, null, Variables.NONE);
    }

    /** @param connector the connector the phrase leads to; {@code null} for a pending phrase */
    private record Declared(String origin, Connector connector, Phrase phrase) {
    }

    private final List<Declared> declared = new ArrayList<>();
    private final List<StepDefinition> definitions = new ArrayList<>();

    /**
     * @throws ConfigurationException if the catalog file is invalid, an alias does not fit the phrase
     *     it stands for, or two phrases can match the same step
     * @throws IllegalStateException if a connector declares an invalid phrase
     */
    static Catalog load(Path workspace, List<Connector> connectors, Context context) {
        Catalog catalog = new Catalog();
        List<Connector> declaring = new ArrayList<>(connectors);
        declaring.add(new BuiltInPhrases());
        for (Connector connector : declaring) {
            String origin = connector instanceof BuiltInPhrases ? "engine" : "connector:" + connector.sourceType();
            for (Phrase phrase : connector.defaultPhrases()) {
                if (!LANGUAGES.contains(phrase.language())) {
                    throw new IllegalStateException("Phrase '" + phrase.pattern() + "' of connector '"
                            + connector.sourceType() + "' has unknown language '" + phrase.language()
                            + "'; the languages are: " + String.join(", ", LANGUAGES.stream().sorted().toList()));
                }
                catalog.declared.add(new Declared(origin, connector, phrase));
            }
        }
        catalog.addAliases(workspace);

        for (Declared phrase : catalog.declared) {
            if (phrase.connector() == null) {
                catalog.definitions.add(new PendingStepDefinition(
                        phrase.phrase().id(), phrase.phrase().pattern(), context.currentStep()));
                continue;
            }
            catalog.definitions.add(new PhraseStepDefinition(phrase.connector().sourceType(), phrase.phrase(),
                    action(phrase.connector(), phrase.phrase()), context.profile(), context.polling(),
                    context.diagnostics(), context.currentStep(), context.variables()));
        }
        catalog.rejectAmbiguity();
        return catalog;
    }

    private static Action action(Connector connector, Phrase phrase) {
        return connector.actions().stream()
                .filter(action -> action.name().equals(phrase.action()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Phrase '" + phrase.pattern() + "' of connector '"
                        + connector.sourceType() + "' targets unknown action '" + phrase.action() + "'"));
    }

    private void addAliases(Path workspace) {
        Path file = workspace.resolve("catalog.yaml");
        if (!Files.isRegularFile(file)) {
            return;
        }
        Map<String, Object> root = YAML.mapping(YAML.parse(file), "");
        YAML.allowOnly(root, "", "aliases", "pending");
        List<Declared> defaults = List.copyOf(declared);
        addAliases(root.get("aliases"), defaults);
        addPending(root.get("pending"), defaults);
    }

    private void addAliases(Object aliases, List<Declared> defaults) {
        if (aliases == null) {
            return;
        }
        if (!(aliases instanceof List<?> list)) {
            throw YAML.invalid("aliases", "expected a list but was " + YamlFile.show(aliases));
        }
        for (int i = 0; i < list.size(); i++) {
            String path = "aliases[" + i + "]";
            Map<String, Object> alias = YAML.mapping(list.get(i), path);
            YAML.allowOnly(alias, path, "phrase", "for", "language");
            String pattern = text(alias.get("phrase"), path + ".phrase");
            String target = text(alias.get("for"), path + ".for");
            Object language = alias.getOrDefault("language", Phrase.ENGLISH);
            if (!(language instanceof String code) || !LANGUAGES.contains(code)) {
                throw YAML.invalid(path + ".language", "expected one of "
                        + String.join(", ", LANGUAGES.stream().sorted().toList()) + " but was "
                        + YamlFile.show(language));
            }

            Declared stands = defaults.stream()
                    .filter(candidate -> candidate.phrase().pattern().equals(target))
                    .findFirst()
                    .orElseThrow(() -> YAML.invalid(path + ".for", "no default phrase is '" + target + "'"));
            Set<String> expected = parameters(target);
            Set<String> given = parameters(pattern);
            if (!given.equals(expected)) {
                throw YAML.invalid(path + ".phrase", "the alias '" + pattern + "' has parameters "
                        + show(given) + ", which are not those of '" + target + "': " + show(expected));
            }
            declared.add(new Declared("workspace", stands.connector(), stands.phrase().in(code, pattern)));
        }
    }

    /**
     * Pending phrases: a wording with an identifier and no implementation. When an installed
     * connector declares a phrase with the same identifier the wording stands for that phrase, as
     * an alias would, so that the features keep working unchanged.
     */
    private void addPending(Object pending, List<Declared> defaults) {
        if (pending == null) {
            return;
        }
        if (!(pending instanceof List<?> list)) {
            throw YAML.invalid("pending", "expected a list but was " + YamlFile.show(pending));
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String path = "pending[" + i + "]";
            Map<String, Object> entry = YAML.mapping(list.get(i), path);
            YAML.allowOnly(entry, path, "id", "phrase", "language");
            String id = text(entry.get("id"), path + ".id");
            String pattern = text(entry.get("phrase"), path + ".phrase");
            Object language = entry.getOrDefault("language", Phrase.ENGLISH);
            if (!(language instanceof String code) || !LANGUAGES.contains(code)) {
                throw YAML.invalid(path + ".language", "expected one of "
                        + String.join(", ", LANGUAGES.stream().sorted().toList()) + " but was "
                        + YamlFile.show(language));
            }
            if (!ids.add(id + "/" + code)) {
                throw YAML.invalid(path + ".id", "the phrase '" + id + "' is already pending in language " + code);
            }

            List<Declared> implementing = defaults.stream()
                    .filter(candidate -> id.equals(candidate.phrase().id()))
                    .toList();
            if (implementing.isEmpty()) {
                declared.add(new Declared("workspace", null, new Phrase(pattern, null, null, code, id)));
                continue;
            }
            Declared stands = implementing.stream()
                    .filter(candidate -> candidate.phrase().language().equals(code))
                    .findFirst()
                    .orElse(implementing.getFirst());
            if (stands.phrase().language().equals(code) && stands.phrase().pattern().equals(pattern)) {
                continue;
            }
            Set<String> expected = parameters(stands.phrase().pattern());
            Set<String> given = parameters(pattern);
            if (!given.equals(expected)) {
                throw YAML.invalid(path + ".phrase", "the pending phrase '" + pattern + "' has parameters "
                        + show(given) + ", which are not those of the phrase '" + id + "' that a connector now "
                        + "declares, '" + stands.phrase().pattern() + "': " + show(expected));
            }
            declared.add(new Declared("workspace", stands.connector(), stands.phrase().in(code, pattern)));
        }
    }

    private static String text(Object value, String path) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw YAML.invalid(path, "expected a text but was " + YamlFile.show(value));
        }
        return text.strip();
    }

    private static Set<String> parameters(String pattern) {
        Set<String> names = new HashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(pattern);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static String show(Set<String> names) {
        return names.isEmpty() ? "none" : String.join(", ", names.stream().sorted().map(n -> "{" + n + "}").toList());
    }

    /** Two phrases that can match the same step would leave Cucumber to refuse it at execution. */
    private void rejectAmbiguity() {
        ParameterTypeRegistry registry = new ParameterTypeRegistry(Locale.ENGLISH);
        registry.defineParameterType(integerOrVariable());
        ExpressionFactory factory = new ExpressionFactory(registry);
        List<Pattern> regexps = definitions.stream()
                .map(definition -> factory.createExpression(definition.getPattern()).getRegexp())
                .toList();
        List<List<String>> samples = definitions.stream()
                .map(definition -> samples(definition.getPattern()))
                .toList();
        for (int first = 0; first < definitions.size(); first++) {
            for (int second = first + 1; second < definitions.size(); second++) {
                if (matchesAny(regexps.get(first), samples.get(second))
                        || matchesAny(regexps.get(second), samples.get(first))) {
                    throw new ConfigurationException("Ambiguous catalog: the phrases '"
                            + declared.get(first).phrase().pattern() + "' (" + declared.get(first).origin()
                            + ") and '" + declared.get(second).phrase().pattern() + "' ("
                            + declared.get(second).origin() + ") can match the same step");
                }
            }
        }
    }

    private static boolean matchesAny(Pattern regexp, List<String> steps) {
        return steps.stream().anyMatch(step -> regexp.matcher(step).matches());
    }

    /** Steps a pattern matches, one for each way its optional and alternative texts can be written. */
    private static List<String> samples(String expression) {
        return variants(expression).stream()
                .map(variant -> variant.replace("{string}", "\"x\"")
                        .replace("{" + INTEGER_OR_VARIABLE + "}", "1"))
                .toList();
    }

    private static List<String> variants(String expression) {
        Matcher optional = OPTIONAL.matcher(expression);
        if (optional.find()) {
            String before = expression.substring(0, optional.start());
            String after = expression.substring(optional.end());
            List<String> variants = new ArrayList<>(variants(before + after));
            variants.addAll(variants(before + optional.group(1) + after));
            return variants;
        }
        Matcher alternation = ALTERNATION.matcher(expression);
        if (alternation.find()) {
            List<String> variants = new ArrayList<>();
            for (String choice : alternation.group().split("/")) {
                variants.addAll(variants(
                        expression.substring(0, alternation.start()) + choice + expression.substring(alternation.end())));
            }
            return variants;
        }
        return List.of(expression);
    }

    static ParameterType<String> integerOrVariable() {
        return new ParameterType<>(INTEGER_OR_VARIABLE, List.of("-?\\d+", "\\$\\{[^}]*}"), String.class,
                (String text) -> text);
    }

    List<StepDefinition> definitions() {
        return List.copyOf(definitions);
    }

    List<CatalogEntry> entries() {
        List<CatalogEntry> entries = new ArrayList<>();
        for (int i = 0; i < declared.size(); i++) {
            Declared phrase = declared.get(i);
            if (definitions.get(i) instanceof PendingStepDefinition pending) {
                entries.add(new CatalogEntry(phrase.phrase().pattern(), phrase.phrase().language(), phrase.origin(),
                        "pending:" + phrase.phrase().id(), null, pending.parameters()));
            } else {
                entries.add(new CatalogEntry(phrase.phrase().pattern(), phrase.phrase().language(), phrase.origin(),
                        phrase.connector().sourceType() + "." + phrase.phrase().action(),
                        phrase.phrase().verification(), ((PhraseStepDefinition) definitions.get(i)).parameters()));
            }
        }
        return entries;
    }
}
