package io.github.bdd.engine;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A configuration file of the workspace, whose problems are reported with the path of the property. */
final class YamlFile {

    private final String name;

    /** @param name the name of the file in the workspace, e.g. {@code sources.yaml} */
    YamlFile(String name) {
        this.name = name;
    }

    Object parse(Path file) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (Reader reader = Files.newBufferedReader(file)) {
            return new Yaml(new SafeConstructor(options)).load(reader);
        } catch (YAMLException | IOException e) {
            throw new ConfigurationException("Cannot read " + file + ": " + e.getMessage());
        }
    }

    /** A missing value counts as an empty mapping, so that an empty section needs no special syntax. */
    Map<String, Object> mapping(Object value, String path) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw invalid(path, "expected a mapping but was " + show(value));
        }
        Map<String, Object> mapping = new LinkedHashMap<>();
        map.forEach((key, entry) -> {
            if (!(key instanceof String)) {
                throw invalid(path, "expected a text as key but was " + show(key));
            }
            mapping.put((String) key, entry);
        });
        return mapping;
    }

    void allowOnly(Map<String, Object> mapping, String path, String... keys) {
        for (String key : mapping.keySet()) {
            if (!List.of(keys).contains(key)) {
                throw invalid(path.isEmpty() ? key : path + "." + key,
                        "unknown property; allowed: " + String.join(", ", keys));
            }
        }
    }

    static String show(Object value) {
        if (value == null) {
            return "missing";
        }
        return value instanceof String ? "'" + value + "'" : value.toString();
    }

    ConfigurationException invalid(String path, String problem) {
        return new ConfigurationException("Invalid " + name + ": " + (path.isEmpty() ? "" : path + ": ") + problem);
    }
}
