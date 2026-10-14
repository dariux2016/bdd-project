package io.github.bdd.engine;

import io.cucumber.core.backend.CucumberInvocationTargetException;
import io.cucumber.core.backend.ParameterInfo;
import io.cucumber.core.backend.StepDefinition;
import io.github.bdd.plugin.Parameter;
import io.github.bdd.plugin.ParameterType;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A phrase that has no implementation yet: the author can use it in features, and executing it
 * leaves the step {@code pending}. Each parameter of the pattern takes a quoted text.
 */
final class PendingStepDefinition implements CatalogStep {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    /** What executing a pending phrase throws, which Cucumber reports as a pending step. */
    @io.cucumber.core.backend.Pending
    static final class PendingPhraseException extends RuntimeException {

        PendingPhraseException(String message) {
            super(message);
        }
    }

    private final String location;
    private final String id;
    private final String expression;
    private final List<Parameter> parameters = new ArrayList<>();
    private final CurrentStep currentStep;

    PendingStepDefinition(String id, String pattern, CurrentStep currentStep) {
        this.id = id;
        this.location = "pending phrase '" + pattern + "' (" + id + ")";
        this.currentStep = currentStep;
        this.expression = PLACEHOLDER.matcher(pattern).replaceAll(match -> {
            parameters.add(new Parameter(match.group(1), ParameterType.STRING));
            return Matcher.quoteReplacement("{string}");
        });
    }

    @Override
    public void execute(Object[] args) {
        throw new CucumberInvocationTargetException(this, new InvocationTargetException(
                new PendingPhraseException("The phrase '" + id + "' has no implementation yet")));
    }

    @Override
    public List<ParameterInfo> parameterInfos() {
        List<ParameterInfo> infos = new ArrayList<>();
        for (int i = 0; i < parameters.size(); i++) {
            infos.add(new PhraseStepDefinition.TypedParameter(String.class));
        }
        if (currentStep != null && !currentStep.lacksArgument()) {
            // The table or doc string of the step, which a pending phrase ignores.
            infos.add(new PhraseStepDefinition.TypedParameter(Object.class));
        }
        return infos;
    }

    @Override
    public List<Parameter> parameters() {
        return List.copyOf(parameters);
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
        return location;
    }
}
