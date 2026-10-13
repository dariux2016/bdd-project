package io.github.bdd.engine;

import com.jayway.jsonpath.JsonPath;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
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
import io.github.bdd.plugin.Verification;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.time.Duration;
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
    private static final Parameter TIMEOUT = new Parameter("timeout", ParameterType.INTEGER);
    private static final Parameter FIELD = new Parameter("field", ParameterType.STRING);
    private static final Parameter VARIABLE = new Parameter("variable", ParameterType.STRING);

    private final String location;
    private final String sourceType;
    private final Profile profile;
    private final Action action;
    /** {@code null} for a phrase that only performs its action. */
    private final Verifier verifier;
    private final Polling polling;
    private final CurrentStep currentStep;
    private final Variables variables;
    private final String expression;
    /** The parameters of the action and of the verification in the order they appear in the phrase. */
    private final List<Parameter> parameters = new ArrayList<>();
    /** The parameter taking the table of the step; {@code null} if the action has none. */
    private final Parameter tableParameter;
    /** The parameter taking the doc string of the step; {@code null} if the action has none. */
    private final Parameter textParameter;

    PhraseStepDefinition(String sourceType, Phrase phrase, Action action, Profile profile, Polling polling,
            Diagnostics diagnostics, CurrentStep currentStep, Variables variables) {
        this.location = "phrase '" + phrase.pattern() + "' of connector '" + sourceType + "'";
        this.sourceType = sourceType;
        this.profile = profile;
        this.action = action;
        this.verifier = phrase.verification() == null ? null : new Verifier(phrase.verification(), currentStep, diagnostics, variables);
        this.polling = polling;
        this.currentStep = currentStep;
        this.variables = variables;
        if (verifier != null && action.kind() == ActionKind.WRITE) {
            // A verification repeats its action, which a write cannot be.
            throw new IllegalStateException("The " + location + " has a verification, so its action '"
                    + action.name() + "' must only read");
        }

        tableParameter = stepArgumentParameter(action, ParameterType.TABLE);
        textParameter = stepArgumentParameter(action, ParameterType.TEXT);
        // The table and the doc string come from the step, not from the text of the phrase.
        Map<String, Parameter> byName = action.parameters().stream()
                .filter(parameter -> parameter != tableParameter && parameter != textParameter)
                .collect(Collectors.toMap(Parameter::name, Function.identity()));
        Set<Parameter> required = new HashSet<>(byName.values());
        int byAction = required.size();
        for (Parameter own : verificationParameters(phrase.verification())) {
            if (byName.put(own.name(), own) != null) {
                throw new IllegalStateException("The " + location + " has a verification, so its action '"
                        + action.name() + "' cannot have a parameter named '" + own.name() + "'");
            }
            if (own == COUNT || own == FIELD || own == VARIABLE) {
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
                    + (required.size() > byAction ? ", and the parameters of its verification" : ""));
        }
    }

    private static Parameter stepArgumentParameter(Action action, ParameterType type) {
        List<Parameter> found = action.parameters().stream().filter(parameter -> parameter.type() == type).toList();
        if (found.size() > 1) {
            throw new IllegalStateException("The action '" + action.name() + "' has more than one "
                    + type + " parameter");
        }
        return found.isEmpty() ? null : found.getFirst();
    }

    private static List<Parameter> verificationParameters(Verification verification) {
        return switch (verification) {
            case null -> List.of();
            case EXISTS, NOT_EXISTS -> List.of(TIMEOUT);
            case COUNT_EXACTLY, COUNT_AT_LEAST, COUNT_AT_MOST -> List.of(COUNT, TIMEOUT);
            case JSON_STRICT, JSON_PARTIAL -> List.of(IGNORED, TIMEOUT);
            case SAVE -> List.of(FIELD, VARIABLE, TIMEOUT);
        };
    }

    private static String expressionType(ParameterType type) {
        return switch (type) {
            case STRING, SOURCE -> "{string}";
            case TABLE, TEXT -> throw new IllegalStateException("A " + type + " parameter is not written in a phrase");
            case INTEGER -> "{" + Catalog.INTEGER_OR_VARIABLE + "}";
        };
    }

    private static Type javaType(ParameterType type) {
        return switch (type) {
            case STRING, SOURCE, INTEGER -> String.class;
            case TABLE, TEXT -> throw new IllegalStateException("A " + type + " parameter is not written in a phrase");
        };
    }

    @Override
    public void execute(Object[] args) {
        try {
            Map<String, Object> values = new HashMap<>();
            List<JsonPath> masked = new ArrayList<>();
            Integer count = null;
            String ignored = null;
            String field = null;
            String variable = null;
            Integer timeout = null;
            for (int i = 0; i < parameters.size(); i++) {
                Parameter parameter = parameters.get(i);
                // The text of the step may cite variables: they are resolved before it is used.
                String text = variables.resolve((String) args[i]);
                if (parameter == COUNT) {
                    count = integer(parameter, text);
                } else if (parameter == IGNORED) {
                    ignored = text;
                } else if (parameter == FIELD) {
                    field = text;
                } else if (parameter == VARIABLE) {
                    variable = (String) args[i];
                } else if (parameter == TIMEOUT) {
                    timeout = integer(parameter, text);
                } else if (parameter.type() == ParameterType.SOURCE) {
                    ConfiguredSource source = source(text);
                    masked.addAll(source.masked());
                    values.put(parameter.name(), source.source());
                } else if (parameter.type() == ParameterType.INTEGER) {
                    values.put(parameter.name(), integer(parameter, text));
                } else {
                    values.put(parameter.name(), text);
                }
            }
            if (verifier == null) {
                Object given = args.length > parameters.size() ? args[parameters.size()] : null;
                action.handler().execute(withStepArgument(values, resolved(given)));
                return;
            }
            Arguments arguments = new Arguments(values);
            // The table or doc string of the step, if any, follows the parameters of the phrase.
            Object stepArgument = resolved(args.length > parameters.size() ? args[parameters.size()] : null);
            Integer expectedCount = count;
            String ignoredPaths = ignored;
            String savedField = field;
            String savedVariable = variable;
            polling(timeout).run(
                    () -> verifier.verify(action.handler().execute(arguments), expectedCount, ignoredPaths,
                            savedField, savedVariable, masked, stepArgument),
                    verifier.lasting());
        } catch (Exception | AssertionError e) {
            throw new CucumberInvocationTargetException(this, new InvocationTargetException(e));
        }
    }

    /** The arguments of the action, with the table or doc string of the step bound to the parameter for it. */
    private Arguments withStepArgument(Map<String, Object> values, Object stepArgument) {
        if (stepArgument instanceof DataTable table) {
            if (tableParameter == null) {
                throw new IllegalArgumentException("This step takes no table");
            }
            values.put(tableParameter.name(), table.cells());
        } else if (stepArgument instanceof DocString docString) {
            if (textParameter == null) {
                throw new IllegalArgumentException("This step takes no doc string");
            }
            values.put(textParameter.name(), docString.getContent());
        }
        return new Arguments(values);
    }

    private static Integer integer(Parameter parameter, String text) {
        try {
            return Integer.valueOf(text.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Expected an integer for {" + parameter.name() + "} but was '" + text + "'");
        }
    }

    /** The table or doc string of a step with the variables it cites replaced by their values. */
    private Object resolved(Object stepArgument) {
        if (stepArgument instanceof DocString docString && Variables.cites(docString.getContent())) {
            return DocString.create(variables.resolve(docString.getContent()), docString.getContentType());
        }
        if (stepArgument instanceof DataTable table
                && table.cells().stream().flatMap(List::stream).anyMatch(Variables::cites)) {
            List<List<String>> cells = new ArrayList<>();
            for (List<String> row : table.cells()) {
                cells.add(row.stream().map(variables::resolve).collect(Collectors.toList()));
            }
            return DataTable.create(cells);
        }
        return stepArgument;
    }

    /** @param timeout the <code>{timeout}</code> of the step, in seconds; {@code null} if the phrase has none */
    private Polling polling(Integer timeout) {
        if (timeout == null) {
            return polling;
        }
        if (timeout < 0) {
            throw new IllegalArgumentException(
                    "The timeout of a step cannot be negative, but was " + timeout + " seconds");
        }
        return polling.within(Duration.ofSeconds(timeout));
    }

    /** The source a step cites, provided the action may run on it. */
    private ConfiguredSource source(String name) {
        ConfiguredSource source = profile.source(name);
        if (!source.type().equals(sourceType)) {
            throw new IllegalArgumentException("Source '" + name + "' of " + profile + " is of type '"
                    + source.type() + "', but this phrase acts on sources of type '" + sourceType + "'");
        }
        if (source.readOnly() && action.kind() == ActionKind.WRITE) {
            throw new IllegalStateException("Source '" + name + "' of " + profile
                    + " is read-only: this step writes to it, so it was not executed");
        }
        return source;
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
        boolean takesStepArgument = verifier != null || tableParameter != null || textParameter != null;
        if (takesStepArgument && !currentStep.lacksArgument()) {
            // Typed as Object, the table or doc string arrives as Cucumber read it.
            infos.add(new TypedParameter(Object.class));
        }
        return infos;
    }

    /** The parameters of the phrase, in the order the pattern uses them. */
    List<Parameter> parameters() {
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
