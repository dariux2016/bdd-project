package io.github.bdd.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** JSON structures as the connectors return them: maps, lists, texts, numbers, booleans and null. */
final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    /** A path that leads nowhere reads as null instead of failing. */
    private static final Configuration PATHS = Configuration.builder()
            .options(Option.SUPPRESS_EXCEPTIONS)
            .build();

    private Json() {
    }

    /** @throws IllegalArgumentException if the text is not a JSON */
    static Object parse(String text) {
        try {
            return MAPPER.readValue(text, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e.getOriginalMessage());
        }
    }

    static String write(Object json) {
        try {
            return MAPPER.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            return String.valueOf(json);
        }
    }

    /**
     * @param path a JSONPath; the leading <code>$.</code> may be omitted, so that a plain field name
     *     is a path too
     * @throws IllegalArgumentException if the path is not a JSONPath
     */
    static JsonPath path(String path) {
        try {
            return JsonPath.compile(path);
        } catch (InvalidPathException | IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + path + "' is not a JSONPath: " + e.getMessage());
        }
    }

    /** The value at a path, or null if there is none. A path with wildcards reads a list of values. */
    static Object read(Object json, JsonPath path) {
        Object value = path.read(json, PATHS);
        return !path.isDefinite() && value instanceof List<?> values && values.isEmpty() ? null : value;
    }

    /** A copy of a JSON without the values at the given paths. */
    static Object without(Object json, List<JsonPath> paths) {
        Object copy = copy(json);
        for (JsonPath path : paths) {
            path.delete(copy, PATHS);
        }
        return copy;
    }

    /** A copy of a JSON with the values at the given paths replaced; paths leading nowhere change nothing. */
    static Object mask(Object json, List<JsonPath> paths, Object replacement) {
        Object copy = copy(json);
        try {
            for (JsonPath path : paths) {
                path.set(copy, replacement, PATHS);
            }
        } catch (RuntimeException e) {
            // A path that cannot be set, such as the root, hides everything rather than risk showing it.
            return replacement;
        }
        return copy;
    }

    private static Object copy(Object json) {
        if (json instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(key, copy(value)));
            return copy;
        }
        if (json instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(value -> copy.add(copy(value)));
            return copy;
        }
        return json;
    }

    /**
     * Where an actual JSON first departs from the expected one. Numbers are equal when their values
     * are, whatever their notation.
     *
     * @param strict whether the actual JSON may not have fields the expected one lacks
     * @return the difference, e.g. <code>at $.total: expected 5 but was 6</code>; {@code null} if none
     */
    static String difference(Object expected, Object actual, boolean strict) {
        return difference(expected, actual, strict, "$");
    }

    private static String difference(Object expected, Object actual, boolean strict, String path) {
        if (expected instanceof Map<?, ?> expectedFields && actual instanceof Map<?, ?> actualFields) {
            for (Map.Entry<?, ?> field : expectedFields.entrySet()) {
                String fieldPath = path + "." + field.getKey();
                if (!actualFields.containsKey(field.getKey())) {
                    return "at " + fieldPath + ": expected " + write(field.getValue()) + " but the field is missing";
                }
                String difference = difference(field.getValue(), actualFields.get(field.getKey()), strict, fieldPath);
                if (difference != null) {
                    return difference;
                }
            }
            if (strict) {
                for (Object field : actualFields.keySet()) {
                    if (!expectedFields.containsKey(field)) {
                        return "at " + path + "." + field + ": unexpected field with value "
                                + write(actualFields.get(field));
                    }
                }
            }
            return null;
        }
        if (expected instanceof List<?> expectedItems && actual instanceof List<?> actualItems) {
            if (expectedItems.size() != actualItems.size()) {
                return "at " + path + ": expected " + expectedItems.size() + " elements but was "
                        + actualItems.size();
            }
            for (int i = 0; i < expectedItems.size(); i++) {
                String difference = difference(expectedItems.get(i), actualItems.get(i), strict, path + "[" + i + "]");
                if (difference != null) {
                    return difference;
                }
            }
            return null;
        }
        boolean equal = expected instanceof Number expectedNumber && actual instanceof Number actualNumber
                ? decimal(expectedNumber).compareTo(decimal(actualNumber)) == 0
                : Objects.equals(expected, actual);
        return equal ? null : "at " + path + ": expected " + write(expected) + " but was " + write(actual);
    }

    static BigDecimal decimal(Number number) {
        return number instanceof BigDecimal decimal ? decimal : new BigDecimal(number.toString());
    }
}
