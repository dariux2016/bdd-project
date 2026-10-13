package io.github.bdd.engine;

import io.github.bdd.plugin.ConfigProperty;
import io.github.bdd.plugin.ConfigType;
import io.github.bdd.plugin.Connector;
import io.github.bdd.plugin.Source;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The sources an execution acts on: those of one profile of the workspace's {@code sources.yaml}. */
final class Profile {

    private static final String FILE = "sources.yaml";
    private static final YamlFile YAML = new YamlFile(FILE);
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]*)}");

    /** {@code null} when the workspace has no configuration, and so no sources. */
    private final String name;
    private final Map<String, ConfiguredSource> sources;

    private Profile(String name, Map<String, ConfiguredSource> sources) {
        this.name = name;
        this.sources = sources;
    }

    /** @param type the source type, which is that of an installed connector */
    record ConfiguredSource(Source source, String type, boolean readOnly) {
    }

    private record Declaration(String type, boolean readOnly, Map<String, Object> config) {
    }

    /**
     * Reads the configuration and resolves the sources of one profile. The structure of every
     * profile is checked, but only the requested one needs its connectors installed and its
     * environment variables set.
     *
     * @param name the requested profile, or {@code null} if none was
     * @throws ConfigurationException if the configuration is invalid or does not fit the request
     */
    static Profile load(Path workspace, String name, Map<String, String> environment, List<Connector> connectors) {
        Path file = workspace.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            if (name != null) {
                throw new ConfigurationException(
                        "Profile '" + name + "' was requested, but there is no " + FILE + " in workspace " + workspace);
            }
            return new Profile(null, Map.of());
        }

        Map<String, Object> root = YAML.mapping(YAML.parse(file), "");
        YAML.allowOnly(root, "", "profiles");
        Map<String, Map<String, Declaration>> profiles = new LinkedHashMap<>();
        YAML.mapping(root.get("profiles"), "profiles").forEach((profile, value) ->
                profiles.put(profile, declarations(value, "profiles." + profile)));
        if (profiles.isEmpty()) {
            throw YAML.invalid("profiles", "at least one profile is required");
        }

        if (name == null) {
            throw new ConfigurationException("No profile was requested; " + FILE + " declares: "
                    + String.join(", ", profiles.keySet()));
        }
        Map<String, Declaration> declarations = profiles.get(name);
        if (declarations == null) {
            throw new ConfigurationException("Profile '" + name + "' is not declared in " + FILE
                    + ", which declares: " + String.join(", ", profiles.keySet()));
        }

        Map<String, ConfiguredSource> sources = new LinkedHashMap<>();
        declarations.forEach((source, declaration) -> sources.put(source, configure(source, declaration,
                "profiles." + name + ".sources." + source, environment, connectors)));
        return new Profile(name, sources);
    }

    private static Map<String, Declaration> declarations(Object profile, String path) {
        Map<String, Object> properties = YAML.mapping(profile, path);
        YAML.allowOnly(properties, path, "sources");
        Map<String, Declaration> declarations = new LinkedHashMap<>();
        YAML.mapping(properties.get("sources"), path + ".sources").forEach((source, value) ->
                declarations.put(source, declaration(value, path + ".sources." + source)));
        return declarations;
    }

    private static Declaration declaration(Object source, String path) {
        Map<String, Object> properties = YAML.mapping(source, path);
        YAML.allowOnly(properties, path, "type", "readOnly", "config");
        if (!(properties.get("type") instanceof String type) || type.isBlank()) {
            throw YAML.invalid(path + ".type",
                    "expected the type of the source but was " + YamlFile.show(properties.get("type")));
        }
        Object readOnly = properties.getOrDefault("readOnly", false);
        if (!(readOnly instanceof Boolean)) {
            throw YAML.invalid(path + ".readOnly", "expected true or false but was " + YamlFile.show(readOnly));
        }
        return new Declaration(type, (Boolean) readOnly, YAML.mapping(properties.get("config"), path + ".config"));
    }

    private static ConfiguredSource configure(String name, Declaration declaration, String path,
            Map<String, String> environment, List<Connector> connectors) {
        Connector connector = connectors.stream()
                .filter(candidate -> candidate.sourceType().equals(declaration.type()))
                .findFirst()
                .orElseThrow(() -> YAML.invalid(path + ".type", "no connector is installed for source type '"
                        + declaration.type() + "'; installed: "
                        + String.join(", ", connectors.stream().map(Connector::sourceType).sorted().toList())));
        List<ConfigProperty> schema = connector.configSchema();

        Map<String, Object> config = new LinkedHashMap<>();
        declaration.config().forEach((property, value) -> {
            String propertyPath = path + ".config." + property;
            ConfigProperty declared = schema.stream()
                    .filter(candidate -> candidate.name().equals(property))
                    .findFirst()
                    .orElseThrow(() -> YAML.invalid(propertyPath, "not a parameter of connector '"
                            + connector.sourceType() + "'; allowed: "
                            + String.join(", ", schema.stream().map(ConfigProperty::name).toList())));
            if (value != null) {
                config.put(property, convert(resolve(value, propertyPath, environment), declared.type(), propertyPath));
            }
        });
        for (ConfigProperty property : schema) {
            if (property.required() && !config.containsKey(property.name())) {
                throw YAML.invalid(path + ".config." + property.name(),
                        "required by connector '" + connector.sourceType() + "'");
            }
        }
        return new ConfiguredSource(new Source(name, config), declaration.type(), declaration.readOnly());
    }

    /** Replaces each <code>${NAME}</code> of a text value with the environment variable of that name. */
    private static Object resolve(Object value, String path, Map<String, String> environment) {
        if (!(value instanceof String text)) {
            return value;
        }
        return VARIABLE.matcher(text).replaceAll(match -> {
            String variable = environment.get(match.group(1));
            if (variable == null) {
                throw YAML.invalid(path, "environment variable '" + match.group(1) + "' is not set");
            }
            return Matcher.quoteReplacement(variable);
        });
    }

    /**
     * Text is converted to the other types, since it is all an environment variable can provide. The
     * reverse is refused: YAML has already reinterpreted an unquoted number, e.g. 0123 as 83.
     */
    private static Object convert(Object value, ConfigType type, String path) {
        switch (type) {
            case STRING -> {
                if (value instanceof String) {
                    return value;
                }
            }
            case INTEGER -> {
                if (value instanceof Integer) {
                    return value;
                }
                if (value instanceof String text) {
                    try {
                        return Integer.valueOf(text.strip());
                    } catch (NumberFormatException e) {
                        // reported below
                    }
                }
            }
            case BOOLEAN -> {
                if (value instanceof Boolean) {
                    return value;
                }
                if (value instanceof String text && Set.of("true", "false").contains(text.strip())) {
                    return Boolean.valueOf(text.strip());
                }
            }
        }
        String expected = switch (type) {
            case STRING -> "a text (write it between quotes)";
            case INTEGER -> "an integer";
            case BOOLEAN -> "true or false";
        };
        throw YAML.invalid(path, "expected " + expected + " but was " + YamlFile.show(value));
    }

    /** @throws IllegalArgumentException if this profile has no source of that name */
    ConfiguredSource source(String sourceName) {
        ConfiguredSource source = sources.get(sourceName);
        if (source == null) {
            throw new IllegalArgumentException("Source '" + sourceName + "' is not defined "
                    + (name == null ? "because the workspace has no " + FILE : "in " + this));
        }
        return source;
    }

    @Override
    public String toString() {
        return "profile '" + name + "'";
    }
}
