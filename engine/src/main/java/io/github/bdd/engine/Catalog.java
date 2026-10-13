package io.github.bdd.engine;

import io.cucumber.cucumberexpressions.Argument;
import io.cucumber.cucumberexpressions.Expression;
import io.cucumber.cucumberexpressions.ExpressionFactory;
import io.cucumber.cucumberexpressions.ParameterType;
import io.cucumber.cucumberexpressions.ParameterTypeRegistry;
import io.github.bdd.plugin.Action;
import io.github.bdd.plugin.Connector;
import io.github.bdd.plugin.Parameter;
import io.github.bdd.plugin.Phrase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 * </pre>
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

    private record Declared(String origin, Connector connector, Phrase phrase) {
    }

    /** A phrase that matched a step, with the values the step gave for its parameters. */
    record Match(CatalogStep step, Object[] arguments) {
    }

    private final List<Declared> declared = new ArrayList<>();
    /** The phrases of the connectors and aliases, in the order of {@link #declared}, then the composite ones. */
    private final List<CatalogStep> definitions = new ArrayList<>();
    private final List<Expression> expressions = new ArrayList<>();
    /** The composite phrases, each with the path of its declaration in the catalog file. */
    private final List<CompositeStepDefinition> composites = new ArrayList<>();
    private final List<String> compositePaths = new ArrayList<>();
    private final List<String> compositeLanguages = new ArrayList<>();

    /**
     * @throws ConfigurationException if the catalog file is invalid, an alias does not fit the phrase
     *     it stands for, a composite phrase uses a step no phrase matches or leads back to itself, or
     *     two phrases can match the same step
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
        Map<String, Object> root = catalogFile(workspace);
        catalog.addAliases(root);

        for (Declared phrase : catalog.declared) {
            catalog.definitions.add(new PhraseStepDefinition(phrase.connector().sourceType(), phrase.phrase(),
                    action(phrase.connector(), phrase.phrase()), context.profile(), context.polling(),
                    context.diagnostics(), context.currentStep(), context.variables()));
        }
        catalog.addComposites(root, context.currentStep());

        ParameterTypeRegistry registry = new ParameterTypeRegistry(Locale.ENGLISH);
        registry.defineParameterType(integerOrVariable());
        ExpressionFactory factory = new ExpressionFactory(registry);
        for (CatalogStep definition : catalog.definitions) {
            catalog.expressions.add(factory.createExpression(definition.getPattern()));
        }
        catalog.rejectAmbiguity();
        catalog.checkComposites();
        return catalog;
    }

    private static Map<String, Object> catalogFile(Path workspace) {
        Path file = workspace.resolve("catalog.yaml");
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        Map<String, Object> root = YAML.mapping(YAML.parse(file), "");
        YAML.allowOnly(root, "", "aliases", "composites");
        return root;
    }

    /** The phrase a step matches, with its arguments; {@code null} if no phrase of the catalog does. */
    Match find(String step) {
        for (int i = 0; i < definitions.size(); i++) {
            Optional<List<Argument<?>>> arguments = expressions.get(i).match(step);
            if (arguments.isPresent()) {
                return new Match(definitions.get(i), arguments.get().stream().map(Argument::getValue).toArray());
            }
        }
        return null;
    }

    private static Action action(Connector connector, Phrase phrase) {
        return connector.actions().stream()
                .filter(action -> action.name().equals(phrase.action()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Phrase '" + phrase.pattern() + "' of connector '"
                        + connector.sourceType() + "' targets unknown action '" + phrase.action() + "'"));
    }

    private void addAliases(Map<String, Object> root) {
        Object aliases = root.get("aliases");
        if (aliases == null) {
            return;
        }
        if (!(aliases instanceof List<?> list)) {
            throw YAML.invalid("aliases", "expected a list but was " + YamlFile.show(aliases));
        }
        List<Declared> defaults = List.copyOf(declared);
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

    private void addComposites(Map<String, Object> root, CurrentStep currentStep) {
        Object entries = root.get("composites");
        if (entries == null) {
            return;
        }
        if (!(entries instanceof List<?> list)) {
            throw YAML.invalid("composites", "expected a list but was " + YamlFile.show(entries));
        }
        for (int i = 0; i < list.size(); i++) {
            String path = "composites[" + i + "]";
            Map<String, Object> composite = YAML.mapping(list.get(i), path);
            YAML.allowOnly(composite, path, "phrase", "language", "parameters", "steps");
            Map<String, String> patterns = compositePatterns(composite, path);
            List<CompositeStepDefinition.Step> steps = compositeSteps(composite.get("steps"), path + ".steps");

            Map<String, io.github.bdd.plugin.ParameterType> types = compositeTypes(composite.get("parameters"), path);
            List<String> names = null;
            for (Map.Entry<String, String> pattern : patterns.entrySet()) {
                List<String> own = new ArrayList<>();
                Matcher matcher = PLACEHOLDER.matcher(pattern.getValue());
                while (matcher.find()) {
                    own.add(matcher.group(1));
                }
                if (new HashSet<>(own).size() != own.size()) {
                    throw YAML.invalid(path + ".phrase", "the phrase '" + pattern.getValue()
                            + "' uses a parameter more than once");
                }
                if (names != null && !new HashSet<>(names).equals(new HashSet<>(own))) {
                    throw YAML.invalid(path + ".phrase", "the phrases of a composite must have the same "
                            + "parameters, but '" + pattern.getValue() + "' has " + show(new HashSet<>(own))
                            + " and the others " + show(new HashSet<>(names)));
                }
                names = own;
            }
            for (String name : types.keySet()) {
                if (!names.contains(name)) {
                    throw YAML.invalid(path + ".parameters." + name, "the phrase has no parameter {" + name + "}");
                }
            }

            for (Map.Entry<String, String> pattern : patterns.entrySet()) {
                List<Parameter> parameters = new ArrayList<>();
                Matcher matcher = PLACEHOLDER.matcher(pattern.getValue());
                StringBuilder expression = new StringBuilder();
                while (matcher.find()) {
                    io.github.bdd.plugin.ParameterType type =
                            types.getOrDefault(matcher.group(1), io.github.bdd.plugin.ParameterType.STRING);
                    parameters.add(new Parameter(matcher.group(1), type));
                    matcher.appendReplacement(expression, Matcher.quoteReplacement(
                            type == io.github.bdd.plugin.ParameterType.INTEGER
                                    ? "{" + INTEGER_OR_VARIABLE + "}" : "{string}"));
                }
                matcher.appendTail(expression);
                CompositeStepDefinition definition = new CompositeStepDefinition(pattern.getValue(),
                        expression.toString(), parameters, steps, this, currentStep);
                definitions.add(definition);
                composites.add(definition);
                compositePaths.add(path);
                compositeLanguages.add(pattern.getKey());
            }
        }
    }

    private static Map<String, String> compositePatterns(Map<String, Object> composite, String path) {
        Map<String, String> patterns = new LinkedHashMap<>();
        Object phrase = composite.get("phrase");
        if (phrase instanceof Map<?, ?>) {
            if (composite.containsKey("language")) {
                throw YAML.invalid(path + ".language", "the phrase already names a language for each wording");
            }
            YAML.mapping(phrase, path + ".phrase").forEach((language, pattern) -> {
                requireLanguage(language, path + ".phrase");
                patterns.put(language, text(pattern, path + ".phrase." + language));
            });
            if (patterns.isEmpty()) {
                throw YAML.invalid(path + ".phrase", "expected at least one wording");
            }
        } else {
            Object language = composite.getOrDefault("language", Phrase.ENGLISH);
            requireLanguage(language, path + ".language");
            patterns.put((String) language, text(phrase, path + ".phrase"));
        }
        return patterns;
    }

    private static void requireLanguage(Object language, String path) {
        if (!(language instanceof String code) || !LANGUAGES.contains(code)) {
            throw YAML.invalid(path, "expected one of "
                    + String.join(", ", LANGUAGES.stream().sorted().toList()) + " but was "
                    + YamlFile.show(language));
        }
    }

    private static Map<String, io.github.bdd.plugin.ParameterType> compositeTypes(Object declared, String path) {
        Map<String, io.github.bdd.plugin.ParameterType> types = new LinkedHashMap<>();
        YAML.mapping(declared, path + ".parameters").forEach((name, type) -> {
            io.github.bdd.plugin.ParameterType parsed = switch (type == null ? "" : type.toString()) {
                case "string" -> io.github.bdd.plugin.ParameterType.STRING;
                case "integer" -> io.github.bdd.plugin.ParameterType.INTEGER;
                default -> throw YAML.invalid(path + ".parameters." + name,
                        "expected string or integer but was " + YamlFile.show(type));
            };
            types.put(name, parsed);
        });
        return types;
    }

    private static List<CompositeStepDefinition.Step> compositeSteps(Object declared, String path) {
        if (!(declared instanceof List<?> list) || list.isEmpty()) {
            throw YAML.invalid(path, "expected a list of at least one step but was " + YamlFile.show(declared));
        }
        List<CompositeStepDefinition.Step> steps = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String stepPath = path + "[" + i + "]";
            Object step = list.get(i);
            if (step instanceof String) {
                steps.add(new CompositeStepDefinition.Step(text(step, stepPath), null, null));
                continue;
            }
            Map<String, Object> mapping = YAML.mapping(step, stepPath);
            YAML.allowOnly(mapping, stepPath, "step", "table", "text");
            String text = text(mapping.get("step"), stepPath + ".step");
            if (mapping.containsKey("table") && mapping.containsKey("text")) {
                throw YAML.invalid(stepPath, "a step has a table or a text, not both");
            }
            List<List<String>> table = null;
            if (mapping.containsKey("table")) {
                if (!(mapping.get("table") instanceof List<?> rows) || rows.isEmpty()) {
                    throw YAML.invalid(stepPath + ".table",
                            "expected a list of rows but was " + YamlFile.show(mapping.get("table")));
                }
                table = new ArrayList<>();
                for (int row = 0; row < rows.size(); row++) {
                    if (!(rows.get(row) instanceof List<?> cells)) {
                        throw YAML.invalid(stepPath + ".table[" + row + "]",
                                "expected a list of cells but was " + YamlFile.show(rows.get(row)));
                    }
                    table.add(cells.stream().map(cell -> cell == null ? "" : cell.toString()).toList());
                }
            }
            Object doc = mapping.get("text");
            if (mapping.containsKey("text") && !(doc instanceof String)) {
                throw YAML.invalid(stepPath + ".text", "expected a text but was " + YamlFile.show(doc));
            }
            steps.add(new CompositeStepDefinition.Step(text, table, (String) doc));
        }
        return steps;
    }

    /**
     * Every step of a composite phrase must match a phrase of the catalog, and following the
     * composite phrases through their steps must never come back to one already followed.
     */
    private void checkComposites() {
        // For each composite phrase, the composite phrases its steps lead to.
        List<List<Integer>> leadsTo = new ArrayList<>();
        for (int c = 0; c < composites.size(); c++) {
            CompositeStepDefinition composite = composites.get(c);
            Map<String, String> examples = new HashMap<>();
            Set<String> integers = new HashSet<>();
            for (Parameter parameter : composite.parameters()) {
                boolean integer = parameter.type() == io.github.bdd.plugin.ParameterType.INTEGER;
                examples.put(parameter.name(), integer ? "1" : "x");
                if (integer) {
                    integers.add(parameter.name());
                }
            }
            List<Integer> targets = new ArrayList<>();
            for (int s = 0; s < composite.steps().size(); s++) {
                String path = compositePaths.get(c) + ".steps[" + s + "]";
                String step = composite.steps().get(s).text();
                String example;
                try {
                    example = CompositeStepDefinition.inText(step, examples, integers);
                } catch (IllegalArgumentException e) {
                    throw YAML.invalid(path, e.getMessage());
                }
                int index = indexOf(example);
                if (index < 0) {
                    throw YAML.invalid(path, "no phrase of the catalog matches the step '" + step + "'");
                }
                if (index >= declared.size()) {
                    targets.add(index - declared.size());
                }
            }
            leadsTo.add(targets);
        }
        Set<Integer> done = new HashSet<>();
        for (int start = 0; start < composites.size(); start++) {
            findCycle(start, leadsTo, new ArrayList<>(), done);
        }
    }

    private int indexOf(String step) {
        for (int i = 0; i < expressions.size(); i++) {
            if (expressions.get(i).match(step).isPresent()) {
                return i;
            }
        }
        return -1;
    }

    private void findCycle(int composite, List<List<Integer>> leadsTo, List<Integer> chain, Set<Integer> done) {
        int position = chain.indexOf(composite);
        if (position >= 0) {
            List<String> names = new ArrayList<>(chain.subList(position, chain.size()).stream()
                    .map(c -> composites.get(c).phrase()).toList());
            names.add(composites.get(composite).phrase());
            throw new ConfigurationException("Composite phrases lead back to themselves: "
                    + String.join(" -> ", names));
        }
        if (!done.add(composite)) {
            return;
        }
        chain.add(composite);
        for (int target : leadsTo.get(composite)) {
            findCycle(target, leadsTo, chain, done);
        }
        chain.remove(chain.size() - 1);
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
        List<Pattern> regexps = expressions.stream().map(Expression::getRegexp).toList();
        List<List<String>> samples = definitions.stream()
                .map(definition -> samples(definition.getPattern()))
                .toList();
        for (int first = 0; first < definitions.size(); first++) {
            for (int second = first + 1; second < definitions.size(); second++) {
                if (matchesAny(regexps.get(first), samples.get(second))
                        || matchesAny(regexps.get(second), samples.get(first))) {
                    throw new ConfigurationException("Ambiguous catalog: the phrases '" + pattern(first) + "' ("
                            + origin(first) + ") and '" + pattern(second) + "' (" + origin(second)
                            + ") can match the same step");
                }
            }
        }
    }

    /** The pattern of a phrase as written in the catalog, by its position among the definitions. */
    private String pattern(int index) {
        return index < declared.size()
                ? declared.get(index).phrase().pattern()
                : composites.get(index - declared.size()).phrase();
    }

    private String origin(int index) {
        return index < declared.size() ? declared.get(index).origin() : "workspace";
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

    List<CatalogStep> definitions() {
        return List.copyOf(definitions);
    }

    List<CatalogEntry> entries() {
        List<CatalogEntry> entries = new ArrayList<>();
        for (int i = 0; i < declared.size(); i++) {
            Declared phrase = declared.get(i);
            entries.add(new CatalogEntry(phrase.phrase().pattern(), phrase.phrase().language(), phrase.origin(),
                    phrase.connector().sourceType() + "." + phrase.phrase().action(),
                    phrase.phrase().verification(), definitions.get(i).parameters()));
        }
        for (int c = 0; c < composites.size(); c++) {
            CompositeStepDefinition composite = composites.get(c);
            entries.add(new CatalogEntry(composite.phrase(), compositeLanguages.get(c), "workspace", "composite",
                    null, composite.parameters(),
                    composite.steps().stream().map(CompositeStepDefinition.Step::text).toList()));
        }
        return entries;
    }
}
