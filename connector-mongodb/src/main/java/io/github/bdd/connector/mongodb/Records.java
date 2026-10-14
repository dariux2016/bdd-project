package io.github.bdd.connector.mongodb;

import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The JSON form of the documents this connector observes. It is stable, so that filters, expected
 * JSONs and saved variables can rely on it:
 *
 * <table>
 *   <caption>BSON values and their JSON form</caption>
 *   <tr><td>ObjectId</td><td>text of its 24 hexadecimal digits</td></tr>
 *   <tr><td>Date</td><td>text in UTC with millisecond precision, e.g. {@code 2024-03-01T10:00:00.000Z}</td></tr>
 *   <tr><td>Decimal128</td><td>number with its exact decimal value, e.g. {@code 19.99}; the text
 *       {@code NaN}, {@code Infinity} or {@code -Infinity} when it has none</td></tr>
 *   <tr><td>Int32, Int64, Double</td><td>number</td></tr>
 *   <tr><td>Binary of subtype UUID</td><td>text of the UUID</td></tr>
 *   <tr><td>other Binary</td><td>text of its Base64 encoding</td></tr>
 *   <tr><td>any other type</td><td>its text</td></tr>
 * </table>
 */
final class Records {

    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Records() {
    }

    static Map<String, Object> json(Document document) {
        Map<String, Object> record = new LinkedHashMap<>();
        document.forEach((name, value) -> record.put(name, value(value)));
        return record;
    }

    private static Object value(Object bson) {
        return switch (bson) {
            case null -> null;
            case String text -> text;
            case Boolean flag -> flag;
            case Integer number -> number;
            case Long number -> number;
            case Double number -> number;
            case ObjectId id -> id.toHexString();
            case Date date -> DATE.format(date.toInstant());
            case Decimal128 decimal -> decimal(decimal);
            case UUID uuid -> uuid.toString();
            case Binary binary -> binary(binary);
            case Document document -> json(document);
            case Map<?, ?> map -> {
                Map<String, Object> converted = new LinkedHashMap<>();
                map.forEach((name, value) -> converted.put(String.valueOf(name), value(value)));
                yield converted;
            }
            case Iterable<?> items -> {
                List<Object> converted = new ArrayList<>();
                items.forEach(item -> converted.add(value(item)));
                yield converted;
            }
            default -> bson.toString();
        };
    }

    private static Object decimal(Decimal128 decimal) {
        if (decimal.isNaN() || decimal.isInfinite()) {
            return decimal.toString();
        }
        BigDecimal exact = decimal.bigDecimalValue();
        // Negative zero has no BigDecimal form; it is zero for every comparison.
        return decimal.isNegative() && exact.signum() == 0 ? BigDecimal.ZERO : exact;
    }

    private static Object binary(Binary binary) {
        if (binary.getType() == 4 && binary.length() == 16) {
            return new org.bson.BsonBinary(binary.getType(), binary.getData()).asUuid().toString();
        }
        return Base64.getEncoder().encodeToString(binary.getData());
    }
}
