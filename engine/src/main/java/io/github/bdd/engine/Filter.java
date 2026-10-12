package io.github.bdd.engine;

import com.jayway.jsonpath.JsonPath;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** The conditions of a <code>field | operator | value</code> table; a record must satisfy them all. */
final class Filter {

    /** The filter of a step without a table, which every record satisfies. */
    static final Filter NONE = new Filter(List.of());

    private static final List<List<String>> HEADERS = List.of(
            List.of("field", "operator", "value"),
            List.of("campo", "operatore", "valore"));

    private final List<Condition> conditions;

    private Filter(List<Condition> conditions) {
        this.conditions = conditions;
    }

    private record Condition(JsonPath field, Operator operator, String value, Pattern regex) {
    }

    /**
     * @param rows the cells of the table, header included
     * @param location where a row is in the feature, by its index, to put in front of its errors
     * @throws IllegalArgumentException if the table is not a filter; the message cites the row
     */
    static Filter parse(List<List<String>> rows, IntFunction<String> location) {
        List<String> header = rows.getFirst().stream().map(cell -> text(cell).toLowerCase(Locale.ROOT)).toList();
        if (!HEADERS.contains(header)) {
            throw new IllegalArgumentException(location.apply(0) + ": a filter table has the columns "
                    + "'field | operator | value' (or 'campo | operatore | valore') but this one has '"
                    + String.join(" | ", header) + "'");
        }
        List<Condition> conditions = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            try {
                conditions.add(condition(text(rows.get(i).get(0)), text(rows.get(i).get(1)), text(rows.get(i).get(2))));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(location.apply(i) + ": " + e.getMessage());
            }
        }
        return new Filter(conditions);
    }

    private static Condition condition(String field, String operatorName, String value) {
        if (field.isEmpty()) {
            throw new IllegalArgumentException("the field is missing");
        }
        Operator operator = Operator.named(operatorName).orElseThrow(() -> new IllegalArgumentException(
                "unknown operator '" + operatorName + "'; the operators are: " + Operator.allNames()));
        if (!operator.takesValue() && !value.isEmpty()) {
            throw new IllegalArgumentException(
                    "operator '" + operatorName + "' takes no value, but '" + value + "' was given");
        }
        if (operator.needsValue() && value.isEmpty()) {
            throw new IllegalArgumentException("operator '" + operatorName + "' needs a value");
        }
        return new Condition(Json.path(field), operator, value, operator == Operator.REGEX ? regex(value) : null);
    }

    private static Pattern regex(String value) {
        try {
            return Pattern.compile(value);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "'" + value + "' is not a regular expression: " + e.getDescription());
        }
    }

    /** An empty cell reaches the engine as null. */
    private static String text(String cell) {
        return cell == null ? "" : cell.strip();
    }

    boolean matches(Object record) {
        return conditions.stream().allMatch(condition -> condition.operator()
                .test(Json.read(record, condition.field()), condition.value(), condition.regex()));
    }
}
