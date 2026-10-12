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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** A phrase of the catalog, exposed to Cucumber as a step definition that runs the phrase's action. */
final class PhraseStepDefinition implements StepDefinition {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    private final String location;
    private final String sourceType;
    private final Profile profile;
    private final Action action;
    private final String expression;
    /** The action parameters in the order they appear in the phrase. */
    private final List<Parameter> parameters = new ArrayList<>();

    PhraseStepDefinition(String sourceType, Phrase phrase, Action action, Profile profile) {
        this.location = "phrase '" + phrase.pattern() + "' of connector '" + sourceType + "'";
        this.sourceType = sourceType;
        this.profile = profile;
        this.action = action;

        Map<String, Parameter> byName = action.parameters().stream()
                .collect(Collectors.toMap(Parameter::name, Function.identity()));
        this.expression = PLACEHOLDER.matcher(phrase.pattern()).replaceAll(match -> {
            Parameter parameter = byName.get(match.group(1));
            if (parameter == null) {
                throw new IllegalStateException("The " + location + " uses '" + match.group(1)
                        + "', which is not a parameter of action '" + action.name() + "'");
            }
            parameters.add(parameter);
            return Matcher.quoteReplacement(expressionType(parameter.type()));
        });
        if (parameters.size() != byName.size() || !new HashSet<>(parameters).containsAll(byName.values())) {
            throw new IllegalStateException("The " + location + " must use every parameter of action '"
                    + action.name() + "' exactly once");
        }
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
            for (int i = 0; i < args.length; i++) {
                Parameter parameter = parameters.get(i);
                values.put(parameter.name(),
                        parameter.type() == ParameterType.SOURCE ? source((String) args[i]) : args[i]);
            }
            action.handler().execute(new Arguments(values));
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

    @Override
    public List<ParameterInfo> parameterInfos() {
        return parameters.stream()
                .<ParameterInfo>map(parameter -> new TypedParameter(javaType(parameter.type())))
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
