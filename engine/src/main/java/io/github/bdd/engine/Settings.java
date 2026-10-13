package io.github.bdd.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** The workspace's {@code settings.yaml}, whose sections are read by the parts of the engine they configure. */
final class Settings {

    static final YamlFile YAML = new YamlFile("settings.yaml");

    private Settings() {
    }

    /**
     * @return the properties of a section of the file; empty if the file or the section is missing
     * @throws ConfigurationException if the file is invalid
     */
    static Map<String, Object> section(Path workspace, String name) {
        Path file = workspace.resolve("settings.yaml");
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        Map<String, Object> root = YAML.mapping(YAML.parse(file), "");
        YAML.allowOnly(root, "", "verification", "diagnostics", "reports");
        return YAML.mapping(root.get(name), name);
    }
}
