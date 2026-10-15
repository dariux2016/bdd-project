package io.github.bdd.engine;

import io.cucumber.core.backend.HookDefinition;
import io.cucumber.core.backend.StepDefinition;
import io.cucumber.cucumberexpressions.Expression;
import io.cucumber.cucumberexpressions.ExpressionFactory;
import io.cucumber.cucumberexpressions.ParameterType;
import io.cucumber.cucumberexpressions.ParameterTypeRegistry;
import io.github.bdd.engine.CompositeStepDefinition.Step;
import io.github.bdd.engine.CompositeStepDefinition.Takes;
import io.github.bdd.plugin.Action;
import io.github.bdd.plugin.Connector;
import io.github.bdd.plugin.Parameter;
import io.github.bdd.plugin.Phrase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The effective catalog: the default phrases of the connectors, those every catalog has, and the
 * aliases and composite phrases the workspace adds in its {@code catalog.yaml}.
 *
 * <pre>
 * aliases:
 *   - phrase: "il negozio {source} contiene {key} = {value}"   # the new wording
 *     for: "the fake store {source} has {key} set to {value}"  # a default phrase it stands for
 *     language: it                                             # optional, en by default
 * composites:
 *   - phrase: "the customer {customer} has an order"           # parameters are texts in quotes
 *     language: en                                             # optional, en by default
 *     steps:                                                   # steps of the catalog, in order
 *       - the fake store "orders" has "{customer}" set to 1
 *       - step: a record exists in the fake store "orders"
 *         table:                                               # or "{table}": that of the composite step
 *           - [field, operator, value]
 *           - [customer, equals, "{customer}"]
 *       - step: a record of the fake store "orders" contains the JSON
 *         docString: '{"customer": "{customer}"}'             # may cite {docString}: that of the composite step
 * pending:
 *   - id: orders.archive                                       # the id of the phrase a connector will declare
 *     phrase: "the order {order} is archived after {days} days"
 *     language: en                                             # optional, en by default
 *     parameters:                                              # optional: the type of each parameter,
 *       days: integer                                          # string (in quotes) by default
 * </pre>
 *
 * <p>A pending phrase is one that no installed connector implements yet: its steps are pending.
 * Once a connector declares a phrase with its id, it leads to that phrase's action, as an alias
 * does, so that the features using it run unchanged.
 */
final class Catalog {

    /** The parameter type of integers, which a step may also give as a variable. */
    static final String INTEGER_OR_VARIABLE = "intvar";

    private static final YamlFile YAML = new YamlFile("catalog.yaml");
    private static final Set<String> LANGUAGES = Set.of(Phrase.ENGLISH, Phrase.ITALIAN);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
    private static final Pattern OPTIONAL = Pattern.compile("\\(([^()]*)\\)");
    private static final Pattern ALTERNATION = Pattern.compile("[^\\s/{}()]+(?:/[^\\s/{}()]+)+");
    /** How a pending phrase types its parameters. */
    private static final String STRING = "string";
    private static final String INTEGER = "integer";
    private static final String PASSED_TABLE = "{" + CompositeStepDefinition.TABLE + "}";

    /** What the step definitions need to run, which describing the catalog does not. */
    record Context(Profile profile, Polling polling, Diagnostics diagnostics, CurrentStep currentStep,
            Variables variables) {

        static final Context NONE = new Context(null, null, null, null, Variables.NONE);
    }

    /**
     * @param pending where the catalog declares the pending phrase this one was, with the types it
     *     gives its parameters; {@code null} if it was never pending
     */
    private record Declared(String origin, Connector connector, Phrase phrase, Activated pending) {

        Declared(String origin, Connector connector, Phrase phrase) {
            this(origin, connector, phrase, null);
        }
    }

    /** A pending phrase that a connector now implements. */
    private record Activated(String path, List<Parameter> parameters) {
    }

    private final Context context;
    private final List<Declared> declared = new ArrayList<>();
    private final List<PhraseStepDefinition> definitions = new ArrayList<>();
    private final List<PendingStepDefinition> pending = new ArrayList<>();
    private final List<CompositeStepDefinition> composites = new ArrayList<>();

    private Catalog(Context context) {
        this.context = context;
    }

    /**
     * @throws ConfigurationException if the catalog file is invalid, an alias does not fit the phrase
     *     it stands for, a step of a composite phrase matches no phrase, composite phrases form a
     *     cycle, or two phrases can match the same step
     * @throws IllegalStateException if a connector declares an invalid phrase
     */
    static Catalog load(Path workspace, List<Connector> connectors, Context context) {
        Catalog catalog = new Catalog(context);
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
        Map<String, Object> file = file(workspace);
        catalog.rejectConflictingIds();
        catalog.addAliases(file.get("aliases"));
        catalog.addPending(file.get("pending"));

        for (Declared phrase : catalog.declared) {
            PhraseStepDefinition definition = new PhraseStepDefinition(phrase.connector().sourceType(),
                    phrase.phrase(), action(phrase.connector(), phrase.phrase()), context.profile(), context.polling(),
                    context.diagnostics(), context.currentStep(), context.variables());
            if (phrase.pending() != null) {
                requireSameParameters(phrase, definition);
            }
            catalog.definitions.add(definition);
        }
        catalog.addComposites(file.get("composites"));
        catalog.rejectAmbiguity();
        catalog.resolveComposites();
        catalog.rejectCycles();
        return catalog;
    }

    private static Action action(Connector connector, Phrase phrase) {
        return connector.actions().stream()
                .filter(action -> action.name().equals(phrase.action()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Phrase '" + phrase.pattern() + "' of connector '"
                        + connector.sourceType() + "' targets unknown action '" + phrase.action() + "'"));
    }

    private static Map<String, Object> file(Path workspace) {
        Path file = workspace.resolve("catalog.yaml");
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        Map<String, Object> root = YAML.mapping(YAML.parse(file), "");
        YAML.allowOnly(root, "", "aliases", "composites", "pending");
        return root;
    }

    private void addAliases(Object aliases) {
        if (aliases == null) {
            return;
        }
        List<?> list = list(aliases, "aliases");
        List<Declared> defaults = List.copyOf(declared);
        for (int i = 0; i < list.size(); i++) {
            String path = "aliases[" + i + "]";
            Map<String, Object> alias = YAML.mapping(list.get(i), path);
            YAML.allowOnly(alias, path, "phrase", "for", "language");
            String pattern = text(alias.get("phrase"), path + ".phrase");
            String target = text(alias.get("for"), path + ".for");
            String code = language(alias, path);

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

    /** Phrases that share an id are the same phrase, so they must lead to the same action and check. */
    private void rejectConflictingIds() {
        Map<String, Declared> byId = new HashMap<>();
        for (Declared phrase : declared) {
            String id = phrase.phrase().id();
            if (id == null) {
                continue;
            }
            Declared first = byId.putIfAbsent(id, phrase);
            if (first != null && (first.connector() != phrase.connector()
                    || !first.phrase().action().equals(phrase.phrase().action())
                    || first.phrase().verification() != phrase.phrase().verification())) {
                throw new IllegalStateException("The phrases '" + first.phrase().pattern() + "' (" + first.origin()
                        + ") and '" + phrase.phrase().pattern() + "' (" + phrase.origin() + ") have the same id '"
                        + id + "' but lead to different actions or checks");
            }
        }
    }

    private void addPending(Object section) {
        if (section == null) {
            return;
        }
        List<?> list = list(section, "pending");
        List<Declared> implemented = declared.stream()
                .filter(phrase -> !phrase.origin().equals("workspace") && phrase.phrase().id() != null)
                .toList();
        for (int i = 0; i < list.size(); i++) {
            String path = "pending[" + i + "]";
            Map<String, Object> entry = YAML.mapping(list.get(i), path);
            YAML.allowOnly(entry, path, "id", "phrase", "language", "parameters");
            String id = text(entry.get("id"), path + ".id");
            String pattern = text(entry.get("phrase"), path + ".phrase");
            String code = language(entry, path);
            List<Parameter> parameters = pendingParameters(pattern, entry.get("parameters"), path);

            List<Declared> implementing = implemented.stream()
                    .filter(phrase -> phrase.phrase().id().equals(id))
                    .toList();
            if (implementing.isEmpty()) {
                pending.add(new PendingStepDefinition(pattern, code, id, parameters, context.currentStep()));
            } else if (implementing.stream().noneMatch(phrase -> phrase.phrase().pattern().equals(pattern))) {
                Declared stands = implementing.stream()
                        .filter(phrase -> phrase.phrase().language().equals(code))
                        .findFirst()
                        .orElse(implementing.getFirst());
                declared.add(new Declared("workspace", stands.connector(), stands.phrase().in(code, pattern),
                        new Activated(path, parameters)));
            }
            // Otherwise a connector declares the very wording, which takes the place of the pending phrase.
        }
    }

    /** The parameters of a pending phrase, in the order its pattern uses them, with the types the catalog gives. */
    private static List<Parameter> pendingParameters(String pattern, Object section, String path) {
        Map<String, Object> types = section == null ? Map.of() : YAML.mapping(section, path + ".parameters");
        List<String> names = new ArrayList<>();
        Matcher placeholder = PLACEHOLDER.matcher(pattern);
        while (placeholder.find()) {
            if (names.contains(placeholder.group(1))) {
                throw YAML.invalid(path + ".phrase", "the parameter {" + placeholder.group(1) + "} is used more than once");
            }
            names.add(placeholder.group(1));
        }
        for (String name : types.keySet()) {
            if (!names.contains(name)) {
                throw YAML.invalid(path + ".parameters." + name, "the phrase has no parameter {" + name + "}");
            }
        }
        List<Parameter> parameters = new ArrayList<>();
        for (String name : names) {
            Object type = types.getOrDefault(name, STRING);
            if (!STRING.equals(type) && !INTEGER.equals(type)) {
                throw YAML.invalid(path + ".parameters." + name, "expected " + STRING + " or " + INTEGER
                        + " but was " + YamlFile.show(type));
            }
            parameters.add(new Parameter(name, INTEGER.equals(type)
                    ? io.github.bdd.plugin.ParameterType.INTEGER
                    : io.github.bdd.plugin.ParameterType.STRING));
        }
        return parameters;
    }

    /**
     * A pending phrase that a connector now implements must take the parameters of the connector's
     * phrase, written the same way, or the features written for it would no longer match.
     */
    private static void requireSameParameters(Declared phrase, PhraseStepDefinition definition) {
        Map<String, String> expected = new TreeMap<>();
        definition.parameters().stream()
                .filter(parameter -> parameter.type() != io.github.bdd.plugin.ParameterType.TEXT
                        && parameter.type() != io.github.bdd.plugin.ParameterType.TABLE)
                .forEach(parameter -> expected.put(parameter.name(), written(parameter)));
        Map<String, String> given = new TreeMap<>();
        phrase.pending().parameters().forEach(parameter -> given.put(parameter.name(), written(parameter)));
        if (!given.equals(expected)) {
            throw YAML.invalid(phrase.pending().path(), "the pending phrase '" + phrase.phrase().pattern()
                    + "' is now implemented by the phrase with id '" + phrase.phrase().id() + "' of connector '"
                    + phrase.connector().sourceType() + "', which takes " + describe(expected)
                    + ", but the pending phrase takes " + describe(given));
        }
    }

    /** How a step writes a parameter: a text in quotes, or a bare integer. */
    private static String written(Parameter parameter) {
        return parameter.type() == io.github.bdd.plugin.ParameterType.INTEGER ? INTEGER : STRING;
    }

    private static String describe(Map<String, String> parameters) {
        return parameters.isEmpty() ? "no parameters" : String.join(", ", parameters.entrySet().stream()
                .map(parameter -> "{" + parameter.getKey() + "} as " + parameter.getValue())
                .toList());
    }

    private void addComposites(Object section) {
        if (section == null) {
            return;
        }
        List<?> list = list(section, "composites");
        for (int i = 0; i < list.size(); i++) {
            String path = "composites[" + i + "]";
            Map<String, Object> composite = YAML.mapping(list.get(i), path);
            YAML.allowOnly(composite, path, "phrase", "language", "steps");
            String pattern = text(composite.get("phrase"), path + ".phrase");
            List<String> names = new ArrayList<>();
            Matcher placeholder = PLACEHOLDER.matcher(pattern);
            while (placeholder.find()) {
                String name = placeholder.group(1);
                if (name.equals(CompositeStepDefinition.TABLE) || name.equals(CompositeStepDefinition.DOC_STRING)) {
                    throw YAML.invalid(path + ".phrase", "{" + name + "} is the " + (name.equals("table")
                            ? "table" : "doc string") + " of the composite step, so no parameter can be named so");
                }
                if (names.contains(name)) {
                    throw YAML.invalid(path + ".phrase", "the parameter {" + name + "} is used more than once");
                }
                names.add(name);
            }

            List<?> written = list(composite.get("steps"), path + ".steps");
            if (written.isEmpty()) {
                throw YAML.invalid(path + ".steps", "a composite phrase needs at least one step");
            }
            List<Step> steps = new ArrayList<>();
            for (int s = 0; s < written.size(); s++) {
                steps.add(step(written.get(s), path + ".steps[" + s + "]"));
            }
            boolean table = steps.stream().anyMatch(Step::passesTable);
            boolean docString = steps.stream().anyMatch(step -> step.docString() != null
                    && step.docString().contains("{" + CompositeStepDefinition.DOC_STRING + "}"));
            if (table && docString) {
                throw YAML.invalid(path + ".steps", "the steps use both the table and the doc string of the "
                        + "composite step, which can only have one of them");
            }
            Takes takes = table ? Takes.TABLE : docString ? Takes.DOC_STRING : Takes.NOTHING;
            composites.add(new CompositeStepDefinition(pattern, language(composite, path), path, names, steps,
                    takes, context.currentStep()));
        }
    }

    /** A step of a composite phrase: its text alone, or a mapping with its table or doc string. */
    private static Step step(Object written, String path) {
        if (written instanceof String text) {
            return new Step(text(text, path), null, false, null);
        }
        Map<String, Object> step = YAML.mapping(written, path);
        YAML.allowOnly(step, path, "step", "table", "docString");
        String text = text(step.get("step"), path + ".step");
        Object table = step.get("table");
        Object docString = step.get("docString");
        if (table != null && docString != null) {
            throw YAML.invalid(path, "a step has a table or a doc string, not both");
        }
        if (docString != null) {
            if (!(docString instanceof String content)) {
                throw YAML.invalid(path + ".docString", "expected a text but was " + YamlFile.show(docString));
            }
            return new Step(text, null, false, content);
        }
        if (table == null) {
            return new Step(text, null, false, null);
        }
        if (PASSED_TABLE.equals(table)) {
            return new Step(text, null, true, null);
        }
        if (!(table instanceof List<?> rows) || rows.isEmpty()) {
            throw YAML.invalid(path + ".table", "expected a list of rows, or '" + PASSED_TABLE
                    + "' for the table of the composite step, but was " + YamlFile.show(table));
        }
        List<List<String>> cells = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            if (!(rows.get(r) instanceof List<?> row)) {
                throw YAML.invalid(path + ".table[" + r + "]", "expected a list of cells but was "
                        + YamlFile.show(rows.get(r)));
            }
            List<String> cellsOfRow = new ArrayList<>();
            for (Object cell : row) {
                if (cell instanceof Map<?, ?> || cell instanceof List<?>) {
                    throw YAML.invalid(path + ".table[" + r + "]", "expected cells but one was " + YamlFile.show(cell));
                }
                // An empty cell reaches the phrase as null, as it does from a feature.
                cellsOfRow.add(cell == null ? null : cell.toString());
            }
            if (!cells.isEmpty() && cellsOfRow.size() != cells.getFirst().size()) {
                throw YAML.invalid(path + ".table[" + r + "]", "the rows of a table need as many cells as the first");
            }
            cells.add(cellsOfRow);
        }
        return new Step(text, cells, false, null);
    }

    /** Finds the phrase each step of a composite phrase leads to, among all those of the catalog. */
    private void resolveComposites() {
        ExpressionFactory factory = expressionFactory();
        List<StepDefinition> all = definitions();
        List<Expression> expressions = all.stream()
                .map(definition -> factory.createExpression(definition.getPattern()))
                .toList();
        for (CompositeStepDefinition composite : composites) {
            for (int s = 0; s < composite.steps().size(); s++) {
                Step step = composite.steps().get(s);
                String sample = composite.sample(step);
                int found = -1;
                for (int d = 0; d < all.size() && found < 0; d++) {
                    if (expressions.get(d).match(sample).isPresent()) {
                        found = d;
                    }
                }
                if (found < 0) {
                    throw YAML.invalid(composite.path() + ".steps[" + s + "]", "no phrase of the catalog matches '"
                            + step.text() + "'; a parameter of the composite phrase is written {name}, in quotes "
                            + "where the phrase expects a text");
                }
                composite.leadsTo(all.get(found), expressions.get(found));
            }
        }
    }

    /** A composite phrase that ends up running itself would never end. */
    private void rejectCycles() {
        Set<CompositeStepDefinition> acyclic = new HashSet<>();
        for (CompositeStepDefinition composite : composites) {
            rejectCycles(composite, new LinkedHashSet<>(), acyclic);
        }
    }

    private static void rejectCycles(CompositeStepDefinition composite, LinkedHashSet<CompositeStepDefinition> chain,
            Set<CompositeStepDefinition> acyclic) {
        if (acyclic.contains(composite)) {
            return;
        }
        if (chain.contains(composite)) {
            List<CompositeStepDefinition> cycle = new ArrayList<>(chain);
            cycle = new ArrayList<>(cycle.subList(cycle.indexOf(composite), cycle.size()));
            cycle.add(composite);
            throw YAML.invalid(cycle.getFirst().path(), "the composite phrases form a cycle: " + String.join(" -> ",
                    cycle.stream().map(phrase -> "'" + phrase.pattern() + "'").toList()));
        }
        chain.add(composite);
        for (CompositeStepDefinition inner : composite.composites()) {
            rejectCycles(inner, chain, acyclic);
        }
        chain.remove(composite);
        acyclic.add(composite);
    }

    private static List<?> list(Object value, String path) {
        if (!(value instanceof List<?> list)) {
            throw YAML.invalid(path, "expected a list but was " + YamlFile.show(value));
        }
        return list;
    }

    private static String language(Map<String, Object> mapping, String path) {
        Object language = mapping.getOrDefault("language", Phrase.ENGLISH);
        if (!(language instanceof String code) || !LANGUAGES.contains(code)) {
            throw YAML.invalid(path + ".language", "expected one of "
                    + String.join(", ", LANGUAGES.stream().sorted().toList()) + " but was "
                    + YamlFile.show(language));
        }
        return code;
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

    private static ExpressionFactory expressionFactory() {
        ParameterTypeRegistry registry = new ParameterTypeRegistry(Locale.ENGLISH);
        registry.defineParameterType(integerOrVariable());
        return new ExpressionFactory(registry);
    }

    /** Two phrases that can match the same step would leave Cucumber to refuse it at execution. */
    private void rejectAmbiguity() {
        ExpressionFactory factory = expressionFactory();
        List<StepDefinition> all = definitions();
        List<String> described = new ArrayList<>();
        declared.forEach(phrase -> described.add("'" + phrase.phrase().pattern() + "' (" + phrase.origin() + ")"));
        pending.forEach(phrase -> described.add("'" + phrase.pattern() + "' (workspace, pending)"));
        composites.forEach(composite -> described.add("'" + composite.pattern() + "' (workspace)"));
        List<Pattern> regexps = all.stream()
                .map(definition -> factory.createExpression(definition.getPattern()).getRegexp())
                .toList();
        List<List<String>> samples = all.stream()
                .map(definition -> samples(definition.getPattern()))
                .toList();
        for (int first = 0; first < all.size(); first++) {
            for (int second = first + 1; second < all.size(); second++) {
                if (matchesAny(regexps.get(first), samples.get(second))
                        || matchesAny(regexps.get(second), samples.get(first))) {
                    throw new ConfigurationException("Ambiguous catalog: the phrases " + described.get(first)
                            + " and " + described.get(second) + " can match the same step");
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

    /**
     * The phrases of the connectors and of the engine, their aliases, the pending phrases, then the
     * composite ones.
     */
    List<StepDefinition> definitions() {
        List<StepDefinition> all = new ArrayList<>(definitions);
        all.addAll(pending);
        all.addAll(composites);
        return all;
    }

    /** The hooks the step definitions need: one to give the reports the detail of the composite phrases. */
    List<HookDefinition> beforeHooks() {
        return composites.isEmpty() ? List.of() : List.of(context.currentStep().scenarioHook());
    }

    List<CatalogEntry> entries() {
        List<CatalogEntry> entries = new ArrayList<>();
        for (int i = 0; i < declared.size(); i++) {
            Declared phrase = declared.get(i);
            entries.add(new CatalogEntry(phrase.phrase().pattern(), phrase.phrase().language(), phrase.origin(),
                    phrase.connector().sourceType() + "." + phrase.phrase().action(), phrase.phrase().id(),
                    phrase.phrase().verification(), definitions.get(i).parameters(), List.of(), false));
        }
        for (PendingStepDefinition phrase : pending) {
            entries.add(new CatalogEntry(phrase.pattern(), phrase.language(), "workspace", null, phrase.id(), null,
                    phrase.parameters(), List.of(), true));
        }
        for (CompositeStepDefinition composite : composites) {
            entries.add(new CatalogEntry(composite.pattern(), composite.language(), "workspace", null, null, null,
                    composite.parameters().stream()
                            .map(name -> new Parameter(name, io.github.bdd.plugin.ParameterType.STRING))
                            .toList(),
                    composite.steps().stream().map(Step::text).toList(), false));
        }
        return entries;
    }
}
