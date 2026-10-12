package io.github.bdd.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The operators of a filter table, by their English name, symbol and Italian name. Each compares an
 * observed value with the text of the table by the type of the observed value, not as text.
 */
enum Operator {
    EQUALS("equals", "=", "uguale"),
    NOT_EQUALS("not equals", "!=", "diverso"),
    CONTAINS("contains", "contiene"),
    REGEX("regex"),
    GREATER_THAN("greater than", ">", "maggiore"),
    LESS_THAN("less than", "<", "minore"),
    IS_NULL("is null", "è nullo"),
    IS_NOT_NULL("is not null", "non è nullo");

    private static final Pattern DATE_THEN_SPACE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}) (.+)");

    private final List<String> names;

    Operator(String... names) {
        this.names = List.of(names);
    }

    static Optional<Operator> named(String name) {
        String normalized = name.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(operator -> operator.names.contains(normalized)).findFirst();
    }

    static String allNames() {
        return String.join(", ", Arrays.stream(values()).flatMap(operator -> operator.names.stream()).toList());
    }

    /** Whether the operator compares with a value, rather than only looking at the observed one. */
    boolean takesValue() {
        return this != IS_NULL && this != IS_NOT_NULL;
    }

    /** Whether an empty value cannot be what the author meant. */
    boolean needsValue() {
        return this == REGEX || this == GREATER_THAN || this == LESS_THAN;
    }

    /**
     * @param actual the observed value, a JSON structure; {@code null} if the record has none
     * @param expected the value of the table; ignored by the operators that take none
     * @param regex the compiled value, for {@link #REGEX} only
     */
    boolean test(Object actual, String expected, Pattern regex) {
        return switch (this) {
            case IS_NULL -> actual == null;
            case IS_NOT_NULL -> actual != null;
            case EQUALS -> equal(actual, expected);
            case NOT_EQUALS -> !equal(actual, expected);
            case CONTAINS -> actual instanceof List<?> items
                    ? items.stream().anyMatch(item -> equal(item, expected))
                    : isScalar(actual) && actual.toString().contains(expected);
            case REGEX -> isScalar(actual) && regex.matcher(actual.toString()).find();
            case GREATER_THAN -> order(actual, expected) instanceof Integer order && order > 0;
            case LESS_THAN -> order(actual, expected) instanceof Integer order && order < 0;
        };
    }

    private static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    private static boolean equal(Object actual, String expected) {
        if (actual instanceof String text) {
            LocalDateTime actualTime = time(text);
            LocalDateTime expectedTime = actualTime == null ? null : time(expected);
            return expectedTime != null ? actualTime.equals(expectedTime) : text.equals(expected);
        }
        if (actual instanceof Number) {
            return order(actual, expected) instanceof Integer order && order == 0;
        }
        if (actual instanceof Boolean flag) {
            return flag.toString().equalsIgnoreCase(expected);
        }
        if (actual == null) {
            return false;
        }
        try {
            return Json.difference(Json.parse(expected), actual, true) == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * How an observed value compares with an expected one: a number as a number, a text as an
     * instant if both are dates, as a number if both are numeric, else alphabetically.
     *
     * @return negative, zero or positive; {@code null} if the two cannot be ordered
     */
    private static Integer order(Object actual, String expected) {
        if (actual instanceof Number number) {
            BigDecimal expectedNumber = number(expected);
            return expectedNumber == null ? null : Json.decimal(number).compareTo(expectedNumber);
        }
        if (actual instanceof String text) {
            LocalDateTime actualTime = time(text);
            LocalDateTime expectedTime = time(expected);
            if (actualTime != null && expectedTime != null) {
                return actualTime.compareTo(expectedTime);
            }
            BigDecimal actualNumber = number(text);
            BigDecimal expectedNumber = number(expected);
            if (actualNumber != null && expectedNumber != null) {
                return actualNumber.compareTo(expectedNumber);
            }
            return text.compareTo(expected);
        }
        return null;
    }

    private static BigDecimal number(String text) {
        try {
            return new BigDecimal(text.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Reads an ISO-8601 date or date-time, also with a space in place of the T, as a UTC time: one
     * with an offset is converted, one without is taken as it is and a date as its midnight.
     *
     * @return {@code null} if the text is neither
     */
    private static LocalDateTime time(String text) {
        String iso = DATE_THEN_SPACE.matcher(text.strip()).replaceFirst("$1T$2");
        try {
            return OffsetDateTime.parse(iso).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException notAnInstant) {
            try {
                return LocalDateTime.parse(iso);
            } catch (DateTimeParseException notADateTime) {
                try {
                    return LocalDate.parse(iso).atStartOfDay();
                } catch (DateTimeParseException notADate) {
                    return null;
                }
            }
        }
    }
}
