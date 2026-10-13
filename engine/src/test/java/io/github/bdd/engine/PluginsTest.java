package io.github.bdd.engine;

import io.github.bdd.plugin.Connector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginsTest {

    private static final String BUILT_IN_FEATURE = """
            Feature: x
              Scenario: y
                Given the fake store "s" has "k" set to 1
            """;

    @TempDir
    Path workspace;

    @Test
    void connectorOfAJarInThePluginsFolderIsUsableLikeTheBuiltInOnes() throws Exception {
        plugin("counter.jar", "counter", "1");
        Files.writeString(workspace.resolve("sources.yaml"), """
                profiles:
                  local:
                    sources:
                      tally:
                        type: counter
                """);
        feature("""
                Feature: External connector
                  Scenario: Counting
                    Then the counter "tally" should answer "1"
                """);

        assertThat(run().successful()).isTrue();
        assertThat(Engine.catalog(workspace)).extracting(CatalogEntry::pattern)
                .contains("the counter {source} should answer {value}");
    }

    @Test
    void pluginsKeepTheirOwnDependenciesEvenWhenTheyShareClassNames() throws Exception {
        plugin("one.jar", "one", "from one");
        plugin("two.jar", "two", "from two");
        Files.writeString(workspace.resolve("sources.yaml"), """
                profiles:
                  local:
                    sources:
                      first:
                        type: one
                      second:
                        type: two
                """);
        feature("""
                Feature: Two plugins
                  Scenario: Each one has its own library
                    Then the one "first" should answer "from one"
                    And the two "second" should answer "from two"
                """);

        assertThat(run().successful()).isTrue();
    }

    @Test
    void twoConnectorsOfTheSameSourceTypeAreRefusedNamingBoth() throws Exception {
        plugin("a.jar", "dup", "a");
        plugin("b.jar", "dup", "b");
        feature(BUILT_IN_FEATURE);

        assertThatThrownBy(this::run)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("dup")
                .hasMessageContaining("a.jar")
                .hasMessageContaining("b.jar");
    }

    @Test
    void pluginDeclaringTheTypeOfABuiltInConnectorIsRefused() throws Exception {
        plugin("clash.jar", "fake", "x");
        feature(BUILT_IN_FEATURE);

        assertThatThrownBy(this::run)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("fake")
                .hasMessageContaining("clash.jar");
    }

    @Test
    void jarThatCannotBeLoadedIsNamedInTheError() throws Exception {
        Files.createDirectories(workspace.resolve("plugins"));
        Files.writeString(workspace.resolve("plugins/broken.jar"), "not a jar");
        feature(BUILT_IN_FEATURE);

        assertThatThrownBy(this::run)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("broken.jar");
        assertThatThrownBy(() -> Engine.catalog(workspace))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("broken.jar");
    }

    @Test
    void connectorThatFailsToInstantiateIsNamedInTheError() throws Exception {
        Path jar = workspace.resolve("plugins/failing.jar");
        Files.createDirectories(jar.getParent());
        buildJar(jar, Map.of("x.Failing", """
                package x;
                import java.util.List;
                import io.github.bdd.plugin.*;
                public class Failing implements Connector {
                    public Failing() { throw new IllegalStateException("boom"); }
                    public String sourceType() { return "failing"; }
                    public List<ConfigProperty> configSchema() { return List.of(); }
                    public List<Action> actions() { return List.of(); }
                    public List<Phrase> defaultPhrases() { return List.of(); }
                }
                """), "x.Failing");
        feature(BUILT_IN_FEATURE);

        assertThatThrownBy(this::run)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("failing.jar");
    }

    @Test
    void workspaceWithoutPluginsFolderUsesOnlyTheBuiltInConnectors() throws Exception {
        Files.writeString(workspace.resolve("sources.yaml"), """
                profiles:
                  local:
                    sources:
                      s:
                        type: fake
                """);
        feature(BUILT_IN_FEATURE);

        assertThat(run().successful()).isTrue();
    }

    private ExecutionResult run() {
        String profile = Files.exists(workspace.resolve("sources.yaml")) ? "local" : null;
        return Engine.run(new ExecutionRequest(workspace, profile, Map.of()));
    }

    private void feature(String content) throws IOException {
        Path features = workspace.resolve("features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("test.feature"), content);
    }

    /**
     * Builds a connector of the given source type as a jar of its own, carrying a private library
     * class whose name is the same in every plugin.
     */
    private void plugin(String jarName, String type, String libraryAnswer) throws Exception {
        String simpleName = type.substring(0, 1).toUpperCase() + type.substring(1) + "Connector";
        Map<String, String> sources = Map.of(
                "lib.Answer", "package lib; public class Answer { public static String get() { return \""
                        + libraryAnswer + "\"; } }",
                "x." + simpleName, """
                        package x;
                        import io.github.bdd.plugin.*;
                        import java.util.List;
                        public class %s implements Connector {
                            public String sourceType() { return "%s"; }
                            public List<ConfigProperty> configSchema() { return List.of(); }
                            public List<Action> actions() {
                                return List.of(new Action("answer", ActionKind.READ,
                                        List.of(new Parameter("source", ParameterType.SOURCE),
                                                new Parameter("value", ParameterType.STRING)),
                                        arguments -> {
                                            if (!lib.Answer.get().equals(arguments.string("value"))) {
                                                throw new AssertionError("expected " + arguments.string("value")
                                                        + " but was " + lib.Answer.get());
                                            }
                                            return List.of();
                                        }));
                            }
                            public List<Phrase> defaultPhrases() {
                                return List.of(new Phrase("the %s {source} should answer {value}", "answer"));
                            }
                        }
                        """.formatted(simpleName, type, type));
        Path jar = workspace.resolve("plugins").resolve(jarName);
        Files.createDirectories(jar.getParent());
        buildJar(jar, sources, "x." + simpleName);
    }

    private static void buildJar(Path jar, Map<String, String> sources, String connectorClass) throws Exception {
        Path build = Files.createTempDirectory("plugin-build");
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            Path pluginApi = Path.of(Connector.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            List<String> arguments = new ArrayList<>();
            Path classes = build.resolve("classes");
            Files.createDirectories(classes);
            arguments.addAll(List.of("-d", classes.toString(), "-classpath", pluginApi.toString()));
            for (Map.Entry<String, String> source : sources.entrySet()) {
                Path file = build.resolve("src").resolve(source.getKey().replace('.', '/') + ".java");
                Files.createDirectories(file.getParent());
                Files.writeString(file, source.getValue());
                arguments.add(file.toString());
            }
            assertThat(compiler.run(null, null, null, arguments.toArray(String[]::new))).isZero();

            try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out)) {
                try (Stream<Path> walk = Files.walk(classes)) {
                    for (Path file : walk.filter(Files::isRegularFile).toList()) {
                        jarOut.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                        jarOut.write(Files.readAllBytes(file));
                        jarOut.closeEntry();
                    }
                }
                jarOut.putNextEntry(new JarEntry("META-INF/services/" + Connector.class.getName()));
                jarOut.write(connectorClass.getBytes());
                jarOut.closeEntry();
            }
        } finally {
            try (Stream<Path> walk = Files.walk(build)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }
}
