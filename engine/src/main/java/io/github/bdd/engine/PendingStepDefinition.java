package io.github.bdd.engine;

import io.cucumber.core.backend.ParameterInfo;
import io.cucumber.core.backend.Pending;
import io.cucumber.core.backend.StepDefinition;
import io.github.bdd.plugin.Parameter;
import io.github.bdd.plugin.ParameterType;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A pending phrase of the catalog: one the workspace uses before any installed connector implements
 * it. Cucumber reports its steps as pending and skips those after them.
 */
final class PendingStepDefinition implements StepDefinition {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    /** Thrown by a step of a pending phrase; Cucumber reports what throws it as pending. */
    @Pending
    static final class PendingPhraseException extends RuntimeException {

        PendingPhraseException(String message) {
            super(message);
        }
    }

    private final String pattern;
    private final String language;
    private final String id;
    /** The parameters in the order the pattern uses them. */
    private final List<Parameter> parameters;
    private final String expression;
    private final CurrentStep currentStep;

    PendingStepDefinition(String pattern, String language, String id, List<Parameter> parameters,
            CurrentStep currentStep) {
        this.pattern = pattern;
        this.language = language;
        this.id = id;
        this.parameters = List.copyOf(parameters);
        this.currentStep = currentStep;
        List<String> types = new ArrayList<>();
        this.parameters.forEach(parameter -> types.add(parameter.type() == ParameterType.INTEGER
                ? "{" + Catalog.INTEGER_OR_VARIABLE + "}"
                : "{string}"));
        Matcher placeholder = PLACEHOLDER.matcher(pattern);
        StringBuilder expression = new StringBuilder();
        for (int i = 0; placeholder.find(); i++) {
            placeholder.appendReplacement(expression, Matcher.quoteReplacement(types.get(i)));
        }
        placeholder.appendTail(expression);
        this.expression = expression.toString();
    }

    String pattern() {
        return pattern;
    }

    String language() {
        return language;
    }

    String id() {
        return id;
    }

    List<Parameter> parameters() {
        return parameters;
    }

    @Override
    public void execute(Object[] args) {
        throw new PendingPhraseException("The phrase '" + pattern + "' is pending: no installed connector "
                + "declares a phrase with id '" + id + "'");
    }

    /**
     * A pending phrase does not know yet whether it will take a table or a doc string, so it takes
     * whatever the step being executed has.
     */
    @Override
    public List<ParameterInfo> parameterInfos() {
        List<ParameterInfo> infos = new ArrayList<>();
        for (int i = 0; i < parameters.size(); i++) {
            infos.add(new PhraseStepDefinition.TypedParameter(String.class));
        }
        if (currentStep != null && !currentStep.lacksArgument()) {
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
        return "pending phrase '" + pattern + "' of the workspace";
    }
}
