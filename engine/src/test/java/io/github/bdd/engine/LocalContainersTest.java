package io.github.bdd.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Sources that declare a container image instead of an address. */
class LocalContainersTest {

    private static final String FEATURE = """
            Feature: Containers
              Scenario: Reading the address
                Given the url of "db" is recorded
            """;

    @TempDir
    Path workspace;

    @BeforeEach
    void clearEvents() {
        RecordingConnector.EVENTS.clear();
    }

    @Test
    void sourceWithAnImageIsStartedAndDestroyedAtTheEndOfTheRun() throws IOException {
        sources("""
                profiles:
                  local:
                    sources:
                      db:
                        type: recording
                        image: recording:1
                """);
        feature(FEATURE);

        assertThat(run().successful()).isTrue();

        assertThat(RecordingConnector.EVENTS)
                .containsExactly("start recording:1", "url container://recording:1", "stop recording:1");
    }

    @Test
    void containerIsDestroyedEvenWhenAScenarioFails() throws IOException {
        sources("""
                profiles:
                  local:
                    sources:
                      db:
                        type: recording
                        image: recording:1
                """);
        feature("""
                Feature: Containers
                  Scenario: Failing
                    Given the fake store "db" has "k" set to 1
                """);

        assertThat(run().successful()).isFalse();

        assertThat(RecordingConnector.EVENTS).containsExactly("start recording:1", "stop recording:1");
    }

    @Test
    void containerSettingsOverrideTheDeclaredConfigurationAndOnlyTheChosenProfileStartsContainers() throws IOException {
        sources("""
                profiles:
                  local:
                    sources:
                      db:
                        type: recording
                        image: recording:1
                        config:
                          url: ignored
                  staging:
                    sources:
                      db:
                        type: recording
                        image: recording:2
                """);
        feature(FEATURE);

        assertThat(run().successful()).isTrue();

        assertThat(RecordingConnector.EVENTS).contains("start recording:1", "url container://recording:1")
                .doesNotContain("start recording:2");
    }

    @Test
    void containersOfASharedPoolOutliveTheRunUntilItIsClosed() throws IOException {
        sources("""
                profiles:
                  local:
                    sources:
                      db:
                        type: recording
                        image: recording:1
                """);
        feature(FEATURE);

        try (LocalContainers containers = new LocalContainers()) {
            ExecutionRequest request = new ExecutionRequest(workspace, "local", Map.of(), null, containers);
            Engine.run(request);
            Engine.run(request);

            assertThat(RecordingConnector.EVENTS).containsExactly(
                    "start recording:1", "url container://recording:1", "url container://recording:1");
        }

        assertThat(RecordingConnector.EVENTS).last().isEqualTo("stop recording:1");
    }

    @Test
    void imageForAConnectorThatCannotStartContainersIsAConfigurationError() throws IOException {
        sources("""
                profiles:
                  local:
                    sources:
                      db:
                        type: fake
                        image: fake:1
                """);
        feature(FEATURE);

        assertThatThrownBy(this::run)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("profiles.local.sources.db.image")
                .hasMessageContaining("Connector 'fake' cannot start containers");
    }

    @Test
    void sourceWithoutImageStillNeedsItsRequiredConfiguration() throws IOException {
        sources("""
                profiles:
                  local:
                    sources:
                      db:
                        type: recording
                """);
        feature(FEATURE);

        assertThatThrownBy(this::run)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("profiles.local.sources.db.config.url");
    }

    @Test
    void sourceWithAnImageStillRejectsUnknownParameters() throws IOException {
        sources("""
                profiles:
                  local:
                    sources:
                      db:
                        type: recording
                        image: recording:1
                        config:
                          port: 5432
                """);
        feature(FEATURE);

        assertThatThrownBy(this::run)
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("profiles.local.sources.db.config.port");
        assertThat(RecordingConnector.EVENTS).isEmpty();
    }

    private ExecutionResult run() {
        return Engine.run(new ExecutionRequest(workspace, "local", Map.of()));
    }

    private void sources(String content) throws IOException {
        Files.writeString(workspace.resolve("sources.yaml"), content);
    }

    private void feature(String content) throws IOException {
        Path file = workspace.resolve("features/containers.feature");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
