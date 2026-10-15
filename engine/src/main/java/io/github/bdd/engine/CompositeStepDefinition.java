package io.github.bdd.engine;

import io.cucumber.core.backend.CucumberInvocationTargetException;
import io.cucumber.core.backend.ParameterInfo;
import io.cucumber.core.backend.StepDefinition;
import io.cucumber.cucumberexpressions.Argument;
import io.cucumber.cucumberexpressions.Expression;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A composite phrase of the catalog, exposed to Cucumber as a step definition that runs, in order,
 * the steps the phrase expands into. Each of them is a step of the catalog, and may be another
 * composite phrase.
 *
 * <p>The steps cite the parameters of the composite phrase as <code>{name}</code> in their text,
 * table and doc string; a step can also receive the table of the composite step, written
 * <code>{table}</code>, or cite its doc string as <code>{docString}</code>.
 */
final class CompositeStepDefinition implements StepDefinition {

    static final String TABLE = "table";
    static final String DOC_STRING = "docString";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
    /** A parameter in the steps: <code>{name}</code>, but not the <code>${name}</code> of a scenario variable. */
    private static final Pattern PARAMETER = Pattern.compile("(?<!\\$)\\{(\\w+)}");

    /** What a composite step carries besides its parameters, for its steps to use. */
    enum Takes { NOTHING, TABLE, DOC_STRING }

    /**
     * A step of the composite phrase, as the catalog writes it.
     *
     * @param table the rows of its table; {@code null} if it has none, or has the table of the composite step
     * @param passesTable whether it has the table of the composite step
     * @param docString its doc string; {@code null} if it has none
     */
    record Step(String text, List<List<String>> table, boolean passesTable, String docString) {
    }

    /** The phrase of the catalog a step leads to, and how its text gives that phrase its parameters. */
    private record Target(StepDefinition definition, Expression expression) {
    }

    /**
     * @param failure why the composite phrase failed or is pending, naming the step; {@code null} if
     *     it passed
     * @param pending whether a step is pending rather than failed
     */
    record Outcome(List<StepResult> steps, String failure, boolean pending) {

        Status status() {
            return failure == null ? Status.PASSED : pending ? Status.PENDING : Status.FAILED;
        }
    }

    private final String pattern;
    private final String language;
    /** Where the catalog declares the phrase, e.g. {@code composites[0]}. */
    private final String path;
    private final List<String> parameters;
    private final List<Step> steps;
    private final Takes takes;
    private final String expression;
    private final CurrentStep currentStep;
    private final List<Target> targets = new ArrayList<>();

    CompositeStepDefinition(String pattern, String language, String path, List<String> parameters, List<Step> steps,
            Takes takes, CurrentStep currentStep) {
        this.pattern = pattern;
        this.language = language;
        this.path = path;
        this.parameters = List.copyOf(parameters);
        this.steps = List.copyOf(steps);
        this.takes = takes;
        this.currentStep = currentStep;
        this.expression = PLACEHOLDER.matcher(pattern).replaceAll("{string}");
    }

    String pattern() {
        return pattern;
    }

    String language() {
        return language;
    }

    String path() {
        return path;
    }

    List<String> parameters() {
        return parameters;
    }

    List<Step> steps() {
        return steps;
    }

    /**
     * The text of a step with each parameter written as a variable, which every parameter type of
     * the catalog accepts: the phrase it matches is the one its parameters lead it to.
     */
    String sample(Step step) {
        return substitute(step.text(), parameters.stream()
                .collect(Collectors.toMap(name -> name, name -> "${" + name + "}")));
    }

    /** Sets the phrase each step leads to, in the order of the steps, once the catalog knows them all. */
    void leadsTo(StepDefinition definition, Expression expression) {
        targets.add(new Target(definition, expression));
    }

    /** The composite phrases among the steps, which the catalog checks for cycles. */
    List<CompositeStepDefinition> composites() {
        return targets.stream()
                .map(Target::definition)
                .filter(definition -> definition instanceof CompositeStepDefinition)
                .map(definition -> (CompositeStepDefinition) definition)
                .toList();
    }

    @Override
    public void execute(Object[] args) {
        try {
            List<String> values = new ArrayList<>();
            for (int i = 0; i < parameters.size(); i++) {
                values.add((String) args[i]);
            }
            Outcome outcome = run(values, args.length > parameters.size() ? args[parameters.size()] : null);
            currentStep.ranInnerSteps(outcome.steps(), detail(outcome.steps()));
            if (outcome.pending()) {
                throw new PendingStepDefinition.PendingPhraseException(outcome.failure());
            }
            if (outcome.failure() != null) {
                throw new AssertionError(outcome.failure());
            }
        } catch (Exception | AssertionError e) {
            throw new CucumberInvocationTargetException(this, new InvocationTargetException(e));
        }
    }

    /**
     * Runs the steps in order, until one fails.
     *
     * @param values the parameters of the composite step, in the order of the pattern
     * @param stepArgument the table or doc string of the composite step; {@code null} if it has neither
     * @throws IllegalArgumentException if the composite step lacks the table or doc string its steps need
     */
    Outcome run(List<String> values, Object stepArgument) {
        requireArgument(stepArgument);
        Map<String, String> byName = new HashMap<>();
        for (int i = 0; i < parameters.size(); i++) {
            byName.put(parameters.get(i), values.get(i));
        }
        List<StepResult> results = new ArrayList<>();
        String failure = null;
        boolean pending = false;
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            String text = substitute(step.text(), byName);
            if (failure != null) {
                results.add(new StepResult(StepResult.INNER_KEYWORD, text, Status.SKIPPED, null));
                continue;
            }
            StepResult result = runStep(i, step, text, byName, stepArgument);
            results.add(result);
            if (result.status() == Status.FAILED || result.status() == Status.PENDING) {
                pending = result.status() == Status.PENDING;
                failure = "Step " + (i + 1) + " of the composite phrase '" + pattern + "', '" + text
                        + "', " + (pending ? "is pending" : "failed: " + result.error());
            }
        }
        return new Outcome(results, failure, pending);
    }

    private void requireArgument(Object stepArgument) {
        switch (takes) {
            case NOTHING -> {
            }
            case TABLE -> {
                if (!(stepArgument instanceof DataTable)) {
                    throw new IllegalArgumentException("This step needs a table, which its composite phrase '"
                            + pattern + "' passes on to its steps");
                }
            }
            case DOC_STRING -> {
                if (!(stepArgument instanceof DocString)) {
                    throw new IllegalArgumentException("This step needs a doc string, which its composite phrase '"
                            + pattern + "' passes on to its steps");
                }
            }
        }
    }

    private StepResult runStep(int index, Step step, String text, Map<String, String> byName, Object stepArgument) {
        Target target = targets.get(index);
        try {
            List<Argument<?>> matched = target.expression().match(text).orElseThrow(() -> new IllegalArgumentException(
                    "'" + text + "' does not match the phrase it was written for, '" + target.expression().getSource()
                            + "', once given the parameters of the composite phrase"));
            List<Object> args = new ArrayList<>();
            for (Argument<?> argument : matched) {
                args.add(argument.getValue());
            }
            Object argument = argument(step, byName, stepArgument);
            IntFunction<String> rows = step.table() == null ? null
                    : row -> "catalog.yaml: " + path + ".steps[" + index + "].table[" + row + "]";
            if (target.definition() instanceof CompositeStepDefinition composite) {
                Outcome outcome = currentStep.withTableAt(rows, () -> composite.run(
                        args.stream().map(value -> (String) value).toList(), argument));
                return new StepResult(StepResult.INNER_KEYWORD, text, outcome.status(),
                        outcome.status() == Status.FAILED ? outcome.failure() : null, outcome.steps());
            }
            if (argument != null) {
                args.add(argument);
            }
            currentStep.withTableAt(rows, () -> {
                target.definition().execute(args.toArray());
                return null;
            });
            return new StepResult(StepResult.INNER_KEYWORD, text, Status.PASSED, null);
        } catch (PendingStepDefinition.PendingPhraseException e) {
            return new StepResult(StepResult.INNER_KEYWORD, text, Status.PENDING, null);
        } catch (Exception | AssertionError e) {
            Throwable cause = e instanceof CucumberInvocationTargetException invocation
                    ? invocation.getInvocationTargetExceptionCause()
                    : e;
            return new StepResult(StepResult.INNER_KEYWORD, text, Status.FAILED,
                    cause.getMessage() != null ? cause.getMessage() : cause.toString());
        }
    }

    /** The table or doc string of a step, with the parameters it cites replaced by their values. */
    private static Object argument(Step step, Map<String, String> byName, Object stepArgument) {
        if (step.passesTable()) {
            return stepArgument;
        }
        if (step.table() != null) {
            List<List<String>> cells = new ArrayList<>();
            for (List<String> row : step.table()) {
                cells.add(row.stream().map(cell -> substitute(cell, byName)).toList());
            }
            return DataTable.create(cells);
        }
        if (step.docString() != null) {
            Map<String, String> withDocString = new HashMap<>(byName);
            if (stepArgument instanceof DocString docString) {
                withDocString.put(DOC_STRING, docString.getContent());
            }
            return DocString.create(substitute(step.docString(), withDocString));
        }
        return null;
    }

    /** Replaces the <code>{name}</code> of the values given; any other text in braces stays as it is. */
    private static String substitute(String text, Map<String, String> values) {
        if (text == null) {
            return null;
        }
        return PARAMETER.matcher(text).replaceAll(match -> Matcher.quoteReplacement(
                values.getOrDefault(match.group(1), match.group())));
    }

    /** The steps as the reports show them inside the composite step. */
    static String detail(List<StepResult> steps) {
        StringBuilder detail = new StringBuilder("Steps of the composite phrase:");
        detail(steps, "  ", detail);
        return detail.toString();
    }

    private static void detail(List<StepResult> steps, String indent, StringBuilder detail) {
        for (StepResult step : steps) {
            detail.append('\n').append(indent).append(step.status().name().toLowerCase()).append(": ").append(step.text());
            if (step.steps().isEmpty() && step.error() != null) {
                step.error().lines().forEach(line -> detail.append('\n').append(indent).append("    ").append(line));
            }
            detail(step.steps(), indent + "  ", detail);
        }
    }

    /**
     * Cucumber refuses a step whose table or doc string has no parameter to go to, and one that
     * lacks them when a parameter awaits them. A composite phrase whose steps use the table or doc
     * string of the composite step declares that parameter unless the step being executed has
     * nothing for it; the phrase then says what it needs.
     */
    @Override
    public List<ParameterInfo> parameterInfos() {
        List<ParameterInfo> infos = new ArrayList<>();
        for (int i = 0; i < parameters.size(); i++) {
            infos.add(new PhraseStepDefinition.TypedParameter(String.class));
        }
        if (takes != Takes.NOTHING && !currentStep.lacksArgument()) {
            infos.add(new PhraseStepDefinition.TypedParameter(Object.class));
        }
        return infos;
    }

    @Override
    public String getPattern() {
        return expression;
    }

    @Override
    public boolean isDefinedAt(StackTraceElement stackTraceElement) {
        return false;
    }

    @Override
    public String getLocation() {
        return "composite phrase '" + pattern + "' of the workspace";
    }
}
