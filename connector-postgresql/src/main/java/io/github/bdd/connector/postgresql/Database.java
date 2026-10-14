package io.github.bdd.connector.postgresql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bdd.plugin.Source;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** What the connector does on a PostgreSQL database, one connection per operation. */
final class Database {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    private Database() {
    }

    private static Connection connect(Source source) throws SQLException {
        Properties properties = new Properties();
        Object username = source.config().get("username");
        if (username != null) {
            properties.setProperty("user", username.toString());
        }
        Object password = source.config().get("password");
        if (password != null) {
            properties.setProperty("password", password.toString());
        }
        // Text is sent untyped, so that the server converts it to the type of the column it fills.
        properties.setProperty("stringtype", "unspecified");
        String url = (String) source.config().get("url");
        Connection connection = new org.postgresql.Driver().connect(url, properties);
        if (connection == null) {
            throw new IllegalArgumentException("'" + url + "' is not a PostgreSQL JDBC URL (jdbc:postgresql://...)");
        }
        return connection;
    }

    /** Deletes every row of the table. */
    static void empty(Source source, String table) throws SQLException {
        try (Connection connection = connect(source); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + qualified(table));
        }
    }

    /** Runs a script of any number of statements, in one transaction. */
    static void script(Source source, String script) throws SQLException {
        try (Connection connection = connect(source)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(script);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * Inserts rows in one transaction.
     *
     * @param rows column name to value, in the order of the columns; {@code null} stands for NULL
     */
    static void insert(Source source, String table, List<Map<String, Object>> rows) throws SQLException {
        String name = qualified(table);
        try (Connection connection = connect(source)) {
            connection.setAutoCommit(false);
            try {
                for (Map<String, Object> row : rows) {
                    insert(connection, name, row);
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private static void insert(Connection connection, String table, Map<String, Object> row) throws SQLException {
        if (row.isEmpty()) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO " + table + " DEFAULT VALUES");
            }
            return;
        }
        String columns = row.keySet().stream().map(Database::column).collect(Collectors.joining(", "));
        String placeholders = "?" + ", ?".repeat(row.size() - 1);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")")) {
            int index = 1;
            for (Object value : row.values()) {
                if (value == null) {
                    statement.setNull(index++, Types.NULL);
                } else {
                    statement.setString(index++, text(value));
                }
            }
            statement.executeUpdate();
        }
    }

    private static String text(Object value) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            try {
                return JSON.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException(e.getOriginalMessage());
            }
        }
        return value.toString();
    }

    /** Every row of a table. */
    static List<Map<String, Object>> rows(Source source, String table) throws SQLException {
        return query(source, "SELECT * FROM " + qualified(table));
    }

    /**
     * The rows a query returns. The query runs in a read-only transaction that is never committed,
     * so that it cannot change the database, whatever it says.
     */
    static List<Map<String, Object>> query(Source source, String sql) throws SQLException {
        try (Connection connection = connect(source)) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
                return records(result);
            } finally {
                connection.rollback();
            }
        }
    }

    private static List<Map<String, Object>> records(ResultSet result) throws SQLException {
        ResultSetMetaData metadata = result.getMetaData();
        List<Map<String, Object>> records = new ArrayList<>();
        while (result.next()) {
            Map<String, Object> record = new LinkedHashMap<>();
            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                record.put(metadata.getColumnLabel(column), value(result, column, metadata.getColumnTypeName(column)));
            }
            records.add(record);
        }
        return records;
    }

    /**
     * A value as JSON: numbers and booleans as such, dates and times as ISO-8601 text, JSON columns
     * as the structure they hold, arrays as lists and anything else as text.
     */
    private static Object value(ResultSet result, int column, String type) throws SQLException {
        return switch (type.toLowerCase()) {
            case "int2", "int4", "int8", "serial", "bigserial", "smallserial", "oid", "numeric", "float4", "float8",
                    "money" -> number(result, column);
            case "bool" -> result.getObject(column) == null ? null : result.getBoolean(column);
            case "date" -> iso(result.getObject(column, LocalDate.class));
            case "timestamp" -> iso(result.getObject(column, LocalDateTime.class));
            case "timestamptz" -> {
                OffsetDateTime time = result.getObject(column, OffsetDateTime.class);
                yield time == null ? null : time.toInstant().toString();
            }
            case "time" -> iso(result.getObject(column, LocalTime.class));
            case "timetz" -> iso(result.getObject(column, OffsetTime.class));
            case "json", "jsonb" -> json(result.getString(column));
            case "bytea" -> {
                byte[] bytes = result.getBytes(column);
                yield bytes == null ? null : Base64.getEncoder().encodeToString(bytes);
            }
            default -> {
                if (type.startsWith("_")) {
                    yield array(result.getArray(column));
                }
                yield result.getString(column);
            }
        };
    }

    private static Object number(ResultSet result, int column) throws SQLException {
        Object value = result.getObject(column);
        if (value instanceof Short small) {
            return small.intValue();
        }
        if (value instanceof Double decimal && (decimal.isNaN() || decimal.isInfinite())
                || value instanceof Float real && (real.isNaN() || real.isInfinite())) {
            return value.toString();
        }
        if (value == null || value instanceof Number) {
            return value;
        }
        // Money and other types that JDBC does not give as numbers.
        return result.getString(column);
    }

    private static String iso(Object time) {
        return time == null ? null : time.toString();
    }

    private static Object json(String text) {
        if (text == null) {
            return null;
        }
        try {
            return JSON.readValue(text, Object.class);
        } catch (JsonProcessingException e) {
            return text;
        }
    }

    private static List<Object> array(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        List<Object> elements = new ArrayList<>();
        try (ResultSet items = array.getResultSet()) {
            ResultSetMetaData metadata = items.getMetaData();
            while (items.next()) {
                // The elements are in the second column; the first is their position.
                elements.add(value(items, 2, metadata.getColumnTypeName(2)));
            }
        }
        return elements;
    }

    /** A table name, optionally with its schema, as the server would fold it. */
    private static String qualified(String table) {
        List<String> parts = List.of(table.strip().split("\\.", -1));
        if (parts.size() > 2) {
            throw new IllegalArgumentException("'" + table + "' is not a table name: expected table or schema.table");
        }
        return parts.stream().map(part -> identifier(part, table)).collect(Collectors.joining("."));
    }

    private static String column(String name) {
        return identifier(name.strip(), name);
    }

    private static String identifier(String part, String whole) {
        if (!IDENTIFIER.matcher(part).matches()) {
            throw new IllegalArgumentException("'" + whole + "' is not a valid name: only letters, digits, '_' and '$' "
                    + "are allowed, and it cannot start with a digit");
        }
        // Quoted, so that reserved words work; lowercase, as the server folds a name that is not quoted.
        return "\"" + part.toLowerCase() + "\"";
    }
}
