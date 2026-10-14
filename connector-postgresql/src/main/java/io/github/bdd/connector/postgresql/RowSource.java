package io.github.bdd.connector.postgresql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bdd.plugin.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The rows to insert, as written in a Gherkin table or in a file of the workspace. They are maps
 * from column name to value, where {@code null} is a NULL.
 */
final class RowSource {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RowSource() {
    }

    /** A header row of column names followed by the rows; an empty cell is NULL. */
    static List<Map<String, Object>> fromTable(List<List<String>> table) {
        if (table.isEmpty()) {
            throw new IllegalArgumentException("The table has no header row with the names of the columns");
        }
        return fromCells(table);
    }

    /**
     * A file with a {@code .json} extension holds an array of objects, in which a missing key leaves
     * the column to its default and {@code null} is NULL. A file with a {@code .csv} extension has
     * a header row of column names and then the rows; an empty value is NULL.
     */
    static List<Map<String, Object>> fromDocument(Document document) {
        String name = document.name().toLowerCase(Locale.ROOT);
        if (name.endsWith(".json")) {
            return fromJson(document);
        }
        if (name.endsWith(".csv")) {
            List<List<String>> cells = csv(document.text());
            if (cells.isEmpty()) {
                throw new IllegalArgumentException("File '" + document.name() + "' has no header row");
            }
            return fromCells(cells);
        }
        throw new IllegalArgumentException("File '" + document.name() + "' is neither a .json nor a .csv file");
    }

    private static List<Map<String, Object>> fromCells(List<List<String>> cells) {
        List<String> header = cells.getFirst().stream().map(String::strip).toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (List<String> cells1 : cells.subList(1, cells.size())) {
            if (cells1.size() != header.size()) {
                throw new IllegalArgumentException("A row has " + cells1.size() + " values but there are "
                        + header.size() + " columns: " + header);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) {
                String value = cells1.get(i);
                row.put(header.get(i), value == null || value.isEmpty() ? null : value);
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> fromJson(Document document) {
        Object root;
        try {
            root = JSON.readValue(document.text(), Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "File '" + document.name() + "' is not valid JSON: " + e.getOriginalMessage());
        }
        if (!(root instanceof List<?> items)) {
            throw new IllegalArgumentException("File '" + document.name() + "' must hold an array of objects");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> object)) {
                throw new IllegalArgumentException("File '" + document.name() + "' must hold an array of objects");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            object.forEach((key, value) -> row.put((String) key, value));
            rows.add(row);
        }
        return rows;
    }

    /** RFC 4180: values separated by commas, quoted with double quotes when they hold a comma, quote or line break. */
    static List<List<String>> csv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        boolean started = false;
        String content = text.startsWith("﻿") ? text.substring(1) : text;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < content.length() && content.charAt(i + 1) == '"') {
                    value.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    value.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                started = true;
            } else if (c == ',') {
                row.add(value.toString());
                value.setLength(0);
                started = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                if (started || value.length() > 0 || !row.isEmpty()) {
                    row.add(value.toString());
                    rows.add(row);
                }
                row = new ArrayList<>();
                value.setLength(0);
                started = false;
            } else {
                value.append(c);
                started = true;
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("The CSV has a quoted value that is never closed");
        }
        if (started || value.length() > 0 || !row.isEmpty()) {
            row.add(value.toString());
            rows.add(row);
        }
        return rows;
    }
}
