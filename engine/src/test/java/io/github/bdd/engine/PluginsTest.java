package io.github.bdd.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Connectors installed by dropping them in the plugins folder of the workspace, as the example one
 * of the {@code connector-example} module, which is never on the class path of these tests.
 */
class PluginsTest {

    /** The example plugin as the build assembles it: its jar and those of its dependencies. */
    private static final Path EXAMPLE = Path.of(System.getProperty("example.plugin", "../connector-example/target/plugin/example"));

    @TempDir
    Path workspace;

    @BeforeEach
    void requireTheExamplePlugin() throws IOException {
        assertThat(EXAMPLE).as("the example plugin, built by the connector-example module").isDirectory();
        write("sources.yaml", """
                profiles:
                  local:
                    sources:
                      notes:
                        type: example
                        config:
                          capacity: 2
                """);
        write("settings.yaml", """
                verification:
                  timeout: 0ms
                """);
    }

    @Test
    void connectorOfAPluginIsUsableInConfigurationAndFeaturesLikeAnIncludedOne() throws IOException {
        install(EXAMPLE, "example");
        write("features/notes.feature", """
                Feature: Notes
                  Scenario: Loading
                    Given the example source "notes" holds the YAML records
                      \"""
                      - {title: shopping, done: false}
                      \"""
                    Then a record exists in the example source "notes"
                      | field | operator | value    |
                      | title | equals   | shopping |
                """);
        write("features/note.feature", """
                # language: it
                Funzionalità: Note
                  Scenario: Capienza
                    Dato la sorgente di esempio "notes" contiene i record YAML
                      \"""
                      [a, b, c]
                      \"""
                """);

        ExecutionResult result = Engine.run(new ExecutionRequest(workspace, "local", Map.of()));

        assertThat(result.scenarios()).extracting(ScenarioResult::name, ScenarioResult::status).containsExactly(
                tuple("Capienza", Status.FAILED),
                tuple("Loading", Status.PASSED));
        assertThat(result.scenarios().getFirst().steps().getFirst().error())
                .contains("The example source 'notes' holds at most 2 records");
        assertThat(Engine.catalog(workspace)).extracting(CatalogEntry::pattern, CatalogEntry::origin)
                .contains(tuple("a record exists in the example source {source}", "connector:example"));
    }

    @Test
    void pluginSeesItsOwnDependenciesNotThoseOfTheTool() throws IOException {
        // The tool parses its YAML files with SnakeYAML 2.7; the example plugin brings 2.2.
        install(EXAMPLE, "example");
        write("features/library.feature", """
                Feature: Library
                  Scenario: Version
                    Then the YAML library of the example source "notes" matches
                      | field   | operator | value     |
                      | name    | equals   | snakeyaml |
                      | version | equals   | 2.2       |
                """);

        ExecutionResult result = Engine.run(new ExecutionRequest(workspace, "local", Map.of()));

        assertThat(result.scenarios().getFirst().steps().getFirst().error()).isNull();
        assertThat(result.successful()).isTrue();
    }

    @Test
    void pendingPhraseBecomesActiveWhenItsConnectorIsDroppedInThePluginsFolder() throws IOException {
        write("catalog.yaml", """
                pending:
                  - id: example.exists
                    phrase: "the notes {source} have a record"
                """);
        write("features/notes.feature", """
                Feature: Notes
                  Scenario: Some
                    Then the notes "notes" have a record
                """);
        write("sources.yaml", """
                profiles:
                  local:
                    sources: {}
                """);

        assertThat(Engine.run(new ExecutionRequest(workspace, "local", Map.of())).scenarios())
                .extracting(ScenarioResult::status).containsExactly(Status.PENDING);

        install(EXAMPLE, "example");
        write("sources.yaml", """
                profiles:
                  local:
                    sources:
                      notes:
                        type: example
                """);
        assertThat(Engine.run(new ExecutionRequest(workspace, "local", Map.of())).scenarios().getFirst().steps())
                .singleElement().satisfies(step -> {
                    assertThat(step.status()).isEqualTo(Status.FAILED);
                    assertThat(step.error()).contains("Expected a matching record");
                });
    }

    @Test
    void twoConnectorsOfTheSameSourceTypeAreRejected() throws IOException {
        install(EXAMPLE, "example");
        install(EXAMPLE, "example-copy");

        assertThatThrownBy(() -> Engine.catalog(workspace))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("'example'")
                .hasMessageContaining("plugins/example")
                .hasMessageContaining("plugins/example-copy");
    }

    @Test
    void connectorOfAPluginCannotTakeTheSourceTypeOfAnIncludedOne() throws IOException {
        // A copy of the strict connector of the tests, which are on the class path.
        String strict = "io/github/bdd/engine/StrictConnector.class";
        byte[] code;
        try (var in = getClass().getClassLoader().getResourceAsStream(strict)) {
            code = in.readAllBytes();
        }
        jar("plugins/strict.jar", Map.of(
                "META-INF/services/io.github.bdd.plugin.Connector",
                "io.github.bdd.engine.StrictConnector\n".getBytes(StandardCharsets.UTF_8),
                strict, code));

        assertThatThrownBy(() -> Engine.catalog(workspace))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("'strict'")
                .hasMessageContaining("class path")
                .hasMessageContaining("plugins/strict.jar");
    }

    @Test
    void jarThatCannotBeReadIsNamed() throws IOException {
        write("plugins/broken.jar", "not a jar");
        write("features/empty.feature", "Feature: Empty\n");

        assertThatThrownBy(() -> Engine.run(new ExecutionRequest(workspace, "local", Map.of())))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("plugins/broken.jar");
    }

    @Test
    void jarWhoseConnectorCannotBeLoadedIsNamed() throws IOException {
        jar("plugins/missing.jar", Map.of("META-INF/services/io.github.bdd.plugin.Connector",
                "com.example.MissingConnector\n".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> Engine.catalog(workspace))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("plugins/missing.jar")
                .hasMessageContaining("com.example.MissingConnector");
    }

    @Test
    void jarWithoutAConnectorIsNamed() throws IOException {
        jar("plugins/library.jar", Map.of("README.txt", "no connector here".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> Engine.catalog(workspace))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("plugins/library.jar")
                .hasMessageContaining("no connector");
    }

    /** Copies a plugin directory into the plugins folder of the workspace. */
    private void install(Path plugin, String name) throws IOException {
        Path target = workspace.resolve("plugins").resolve(name);
        Files.createDirectories(target);
        try (Stream<Path> jars = Files.list(plugin)) {
            for (Path jar : jars.toList()) {
                Files.copy(jar, target.resolve(jar.getFileName()));
            }
        }
    }

    private void jar(String name, Map<String, byte[]> entries) throws IOException {
        Path file = workspace.resolve(name);
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file); JarOutputStream jar = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
    }

    private void write(String name, String content) throws IOException {
        Path file = workspace.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
