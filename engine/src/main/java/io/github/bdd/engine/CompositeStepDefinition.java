package io.github.bdd.engine;

import io.cucumber.core.backend.CucumberInvocationTargetException;
import io.cucumber.core.backend.ParameterInfo;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.github.bdd.plugin.Parameter;
import io.github.bdd.plugin.ParameterType;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A composite phrase: a business sentence that stands for a sequence of other phrases, which it
 * runs in order with its own parameters.
 *
 * <p>In the text of an inner step a parameter written <code>{name}</code> is replaced by the value
 * the step gave, between double quotes unless the parameter is an integer or the placeholder is
 * already between quotes, in which case the value is put in as it is. In the cells of a table and
 * in a multiline text the value is always put in as it is.
 */
final class CompositeStepDefinition implements CatalogStep {

    /**
     * A step of the composite, as the catalog gives it.
     *
     * @param text the text of the step, citing the parameters of the composite
     * @param table the cells of the table of the step; {@code null} if it has none
     * @param doc the multiline text of the step; {@code null} if it has none
     */
    record Step(String text, List<List<String>> table, String doc) {
    }

    /** A placeholder in the text of a step: quoted by its author, or bare. A {@code ${...}} is a variable. */
    private static final Pattern IN_TEXT = Pattern.compile("(?<!\\$)([\"'])\\{(\\w+)}\\1|(?<!\\$)\\{(\\w+)}");
    private static final Pattern IN_DATA = Pattern.compile("(?<!\\$)\\{(\\w+)}");

    private final String pattern;
    private final String expression;
    private final List<Parameter> parameters;
    private final List<Step> steps;
    private final Catalog catalog;
    private final CurrentStep currentStep;

    CompositeStepDefinition(String pattern, String expression, List<Parameter> parameters, List<Step> steps,
            Catalog catalog, CurrentStep currentStep) {
        this.pattern = pattern;
        this.expression = expression;
        this.parameters = List.copyOf(parameters);
        this.steps = List.copyOf(steps);
        this.catalog = catalog;
        this.currentStep = currentStep;
    }

    /**
     * @param values the value of each parameter, or an example of one
     * @param integers the names of the integer parameters
     * @throws IllegalArgumentException if the text cites a parameter the composite does not have
     */
    static String inText(String text, Map<String, String> values, Set<String> integers) {
        return IN_TEXT.matcher(text).replaceAll(match -> {
            boolean quoted = match.group(2) != null;
            String name = quoted ? match.group(2) : match.group(3);
            String value = values.get(name);
            if (value == null) {
                throw new IllegalArgumentException("'{" + name + "}' is not a parameter of the phrase; its "
                        + "parameters are " + String.join(", ", values.keySet().stream().sorted()
                                .map(parameter -> "{" + parameter + "}").toList()));
            }
            if (quoted) {
                return Matcher.quoteReplacement(match.group(1) + value + match.group(1));
            }
            return Matcher.quoteReplacement(integers.contains(name) ? value : "\"" + value.replace("\"", "\\\"") + "\"");
        });
    }

    /** What a table cell or a multiline text becomes: the parameters it cites are put in as they are. */
    static String inData(String text, Map<String, String> values) {
        return IN_DATA.matcher(text).replaceAll(match -> {
            String value = values.get(match.group(1));
            return Matcher.quoteReplacement(value == null ? match.group() : value);
        });
    }

    @Override
    public void execute(Object[] args) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < parameters.size(); i++) {
            values.put(parameters.get(i).name(), (String) args[i]);
        }
        Set<String> integers = new HashSet<>();
        parameters.stream().filter(parameter -> parameter.type() == ParameterType.INTEGER)
                .forEach(parameter -> integers.add(parameter.name()));

        List<StepResult> done = new ArrayList<>();
        try {
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                String text = inText(step.text(), values, integers);
                String outer = currentStep.enterInner("step " + (i + 1) + " of the phrase '" + pattern + "'");
                currentStep.expanded(List.of());
                try {
                    Catalog.Match match = catalog.find(text);
                    if (match == null) {
                        throw new IllegalArgumentException("no phrase of the catalog matches the step '" + text + "'");
                    }
                    match.step().execute(withArgument(match.arguments(), step, values));
                    done.add(new StepResult("*", text, Status.PASSED, null, currentStep.inner()));
                } catch (Exception | AssertionError e) {
                    Throwable cause = e instanceof CucumberInvocationTargetException wrapped
                            ? wrapped.getInvocationTargetExceptionCause() : e;
                    String reason = cause.getMessage() != null ? cause.getMessage() : cause.toString();
                    done.add(new StepResult("*", text, Status.FAILED, reason, currentStep.inner()));
                    for (int skipped = i + 1; skipped < steps.size(); skipped++) {
                        done.add(new StepResult("*", inText(steps.get(skipped).text(), values, integers),
                                Status.SKIPPED, null));
                    }
                    throw new CucumberInvocationTargetException(this, new InvocationTargetException(
                            new AssertionError("Inner step " + (i + 1) + " of " + steps.size() + " of '" + pattern
                                    + "' failed: " + text + ": " + reason)));
                } finally {
                    currentStep.leaveInner(outer);
                }
            }
        } finally {
            currentStep.expanded(done);
        }
    }

    private static Object[] withArgument(Object[] arguments, Step step, Map<String, String> values) {
        if (step.table() == null && step.doc() == null) {
            return arguments;
        }
        Object[] all = Arrays.copyOf(arguments, arguments.length + 1);
        if (step.table() != null) {
            List<List<String>> cells = new ArrayList<>();
            for (List<String> row : step.table()) {
                cells.add(row.stream().map(cell -> inData(cell, values)).toList());
            }
            all[arguments.length] = DataTable.create(cells);
        } else {
            all[arguments.length] = DocString.create(inData(step.doc(), values));
        }
        return all;
    }

    @Override
    public List<Parameter> parameters() {
        return parameters;
    }

    List<Step> steps() {
        return steps;
    }

    String phrase() {
        return pattern;
    }

    @Override
    public List<ParameterInfo> parameterInfos() {
        return parameters.stream()
                .<ParameterInfo>map(parameter -> new PhraseStepDefinition.TypedParameter(String.class))
                .toList();
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
        return "composite phrase '" + pattern + "'";
    }
}
