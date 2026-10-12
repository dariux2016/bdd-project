package io.github.bdd.engine;

import io.cucumber.core.backend.CucumberInvocationTargetException;
import io.cucumber.core.backend.ParameterInfo;
import io.cucumber.core.backend.StepDefinition;
import io.cucumber.core.backend.TypeResolver;
import io.github.bdd.engine.Profile.ConfiguredSource;
import io.github.bdd.plugin.Action;
import io.github.bdd.plugin.ActionKind;
import io.github.bdd.plugin.Arguments;
import io.github.bdd.plugin.Parameter;
import io.github.bdd.plugin.ParameterType;
import io.github.bdd.plugin.Phrase;
import io.github.bdd.plugin.Source;
import io.github.bdd.plugin.Verification;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** A phrase of the catalog, exposed to Cucumber as a step definition that runs the phrase's action. */
final class PhraseStepDefinition implements StepDefinition {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
    /** The parameters of the verifications, which a phrase uses like those of its action. */
    private static final Parameter COUNT = new Parameter("count", ParameterType.INTEGER);
    private static final Parameter IGNORED = new Parameter("ignored", ParameterType.STRING);

    private final String location;
    private final String sourceType;
    private final Profile profile;
    private final Action action;
    /** {@code null} for a phrase that only performs its action. */
    private final Verifier verifier;
    private final CurrentStep currentStep;
    private final String expression;
    /** The parameters of the action and of the verification in the order they appear in the phrase. */
    private final List<Parameter> parameters = new ArrayList<>();

    PhraseStepDefinition(String sourceType, Phrase phrase, Action action, Profile profile, CurrentStep currentStep) {
        this.location = "phrase '" + phrase.pattern() + "' of connector '" + sourceType + "'";
        this.sourceType = sourceType;
        this.profile = profile;
        this.action = action;
        this.verifier = phrase.verification() == null ? null : new Verifier(phrase.verification(), currentStep);
        this.currentStep = currentStep;

        Map<String, Parameter> byName = action.parameters().stream()
                .collect(Collectors.toMap(Parameter::name, Function.identity()));
        Set<Parameter> required = new HashSet<>(byName.values());
        for (Parameter own : verificationParameters(phrase.verification())) {
            if (byName.put(own.name(), own) != null) {
                throw new IllegalStateException("The " + location + " has a verification, so its action '"
                        + action.name() + "' cannot have a parameter named '" + own.name() + "'");
            }
            if (own == COUNT) {
                required.add(own);
            }
        }
        this.expression = PLACEHOLDER.matcher(phrase.pattern()).replaceAll(match -> {
            Parameter parameter = byName.get(match.group(1));
            if (parameter == null) {
                throw new IllegalStateException("The " + location + " uses '" + match.group(1)
                        + "', which is not a parameter of action '" + action.name() + "'");
            }
            parameters.add(parameter);
            return Matcher.quoteReplacement(expressionType(parameter.type()));
        });
        if (new HashSet<>(parameters).size() != parameters.size() || !parameters.containsAll(required)) {
            throw new IllegalStateException("The " + location + " must use every parameter of action '"
                    + action.name() + "' exactly once"
                    + (required.contains(COUNT) ? ", and {count} for its verification" : ""));
        }
    }

    private static List<Parameter> verificationParameters(Verification verification) {
        return switch (verification) {
            case null -> List.of();
            case EXISTS, NOT_EXISTS -> List.of();
            case COUNT_EXACTLY, COUNT_AT_LEAST, COUNT_AT_MOST -> List.of(COUNT);
            case JSON_STRICT, JSON_PARTIAL -> List.of(IGNORED);
        };
    }

    private static String expressionType(ParameterType type) {
        return switch (type) {
            case STRING, SOURCE -> "{string}";
            case INTEGER -> "{int}";
        };
    }

    private static Type javaType(ParameterType type) {
        return switch (type) {
            case STRING, SOURCE -> String.class;
            case INTEGER -> Integer.class;
        };
    }

    @Override
    public void execute(Object[] args) {
        try {
            Map<String, Object> values = new HashMap<>();
            Integer count = null;
            String ignored = null;
            for (int i = 0; i < parameters.size(); i++) {
                Parameter parameter = parameters.get(i);
                if (parameter == COUNT) {
                    count = (Integer) args[i];
                } else if (parameter == IGNORED) {
                    ignored = (String) args[i];
                } else {
                    values.put(parameter.name(),
                            parameter.type() == ParameterType.SOURCE ? source((String) args[i]) : args[i]);
                }
            }
            List<?> records = action.handler().execute(new Arguments(values));
            if (verifier != null) {
                // The table or doc string of the step, if any, follows the parameters of the phrase.
                verifier.verify(records, count, ignored,
                        args.length > parameters.size() ? args[parameters.size()] : null);
            }
        } catch (Exception | AssertionError e) {
            throw new CucumberInvocationTargetException(this, new InvocationTargetException(e));
        }
    }

    /** The source a step cites, provided the action may run on it. */
    private Source source(String name) {
        ConfiguredSource source = profile.source(name);
        if (!source.type().equals(sourceType)) {
            throw new IllegalArgumentException("Source '" + name + "' of " + profile + " is of type '"
                    + source.type() + "', but this phrase acts on sources of type '" + sourceType + "'");
        }
        if (source.readOnly() && action.kind() == ActionKind.WRITE) {
            throw new IllegalStateException("Source '" + name + "' of " + profile
                    + " is read-only: this step writes to it, so it was not executed");
        }
        return source.source();
    }

    /**
     * Cucumber refuses a step whose table or doc string has no parameter to go to, and one that
     * lacks them when a parameter awaits them. A phrase with a verification takes steps of both
     * kinds, so it declares that parameter unless the step being executed has nothing for it; the
     * verification then says which kind it needs.
     */
    @Override
    public List<ParameterInfo> parameterInfos() {
        List<ParameterInfo> infos = new ArrayList<>();
        for (Parameter parameter : parameters) {
            infos.add(new TypedParameter(javaType(parameter.type())));
        }
        if (verifier != null && !currentStep.lacksArgument()) {
            // Typed as Object, the table or doc string arrives as Cucumber read it.
            infos.add(new TypedParameter(Object.class));
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
        return location;
    }

    private record TypedParameter(Type type) implements ParameterInfo {

        @Override
        public Type getType() {
            return type;
        }

        @Override
        public boolean isTransposed() {
            return false;
        }

        @Override
        public TypeResolver getTypeResolver() {
            return () -> type;
        }
    }
}
