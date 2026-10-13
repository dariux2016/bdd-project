package io.github.bdd.engine;

import com.jayway.jsonpath.JsonPath;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * How much of what a failed verification observed is reported, so that a failure explains itself
 * without exposing the content of a source.
 *
 * @param maxRecords the most records shown
 * @param maxSize the most characters of JSON shown, for all the records together
 */
record Diagnostics(int maxRecords, int maxSize) {

    private static final YamlFile YAML = Settings.YAML;
    private static final Diagnostics DEFAULT = new Diagnostics(10, 2000);

    /** What takes the place of the value of a masked field. */
    static final String MASK = "***";

    /**
     * Reads the limits from the workspace's {@code settings.yaml}; what the file leaves out has the
     * defaults.
     *
     * @throws ConfigurationException if the settings are invalid
     */
    static Diagnostics load(Path workspace) {
        Map<String, Object> diagnostics = Settings.section(workspace, "diagnostics");
        YAML.allowOnly(diagnostics, "diagnostics", "maxRecords", "maxSize");
        return new Diagnostics(
                limit(diagnostics, "maxRecords", DEFAULT.maxRecords()),
                limit(diagnostics, "maxSize", DEFAULT.maxSize()));
    }

    private static int limit(Map<String, Object> diagnostics, String name, int byDefault) {
        Object value = diagnostics.get(name);
        if (value == null) {
            return byDefault;
        }
        if (!(value instanceof Integer limit) || limit < 0) {
            throw YAML.invalid("diagnostics." + name,
                    "expected a number of at least zero but was " + YamlFile.show(value));
        }
        return limit;
    }

    /** A piece of observed data quoted in a message, cut at the size limit. */
    String clip(String text) {
        return text.length() <= maxSize ? text : text.substring(0, maxSize) + "...";
    }

    /**
     * The records as the lines of a failure message: each in JSON with the masked fields hidden,
     * as many as the limits allow, then how many were left out. A record is shown whole or not at all.
     */
    String show(List<?> records, List<JsonPath> masked) {
        List<String> lines = new ArrayList<>();
        int size = 0;
        int shown = 0;
        for (Object record : records) {
            String json = Json.write(Json.mask(record, masked, MASK));
            if (shown == maxRecords || size + json.length() > maxSize) {
                break;
            }
            lines.add("  " + json);
            size += json.length();
            shown++;
        }
        if (records.isEmpty()) {
            return "Observed records: none";
        }
        int omitted = records.size() - shown;
        if (omitted > 0) {
            lines.add("  (" + omitted + " more " + (omitted == 1 ? "record" : "records") + " omitted)");
        }
        return "Observed records:\n" + String.join("\n", lines);
    }
}
