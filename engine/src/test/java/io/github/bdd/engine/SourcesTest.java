package io.github.bdd.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class SourcesTest {

    private static final String PASSING_FEATURE = """
            Feature: Store
              Scenario: Setting a key
                Given the fake store "fruit" has "apples" set to 5
            """;

    @TempDir
    Path workspace;

    @Test
    void sameFeatureActsOnTheSourcesOfTheChosenProfile() throws IOException {
        sources("""
                profiles:
                  local:
                    sources:
                      fruit:
                        type: fake
                        config:
                          default: 1
                  staging:
                    sources:
                      fruit:
                        type: fake
                        config:
                          default: 2
                """);
        feature("""
                Feature: Store
                  Scenario: Reading a key that was never set
                    Then the fake store "fruit" should have "apples" equal to 2
                """);

        assertThat(run("staging").successful()).isTrue();
        assertThat(run("local").scenarios().getFirst().steps().getFirst().error())
                .isEqualTo("Expected \"apples\" to equal 2 but was 1");
    }

    @Test
    void sourcesOfTheSameTypeHoldSeparateData() throws IOException {
        sources("""
                profiles:
                  local:
                    sources:
                      fruit:
                        type: fake
                      vegetables:
                        type: fake
                """);
        feature("""
                Feature: Store
                  Scenario: Two stores
                    Given the fake store "fruit" has "stock" set to 5
                    And the fake store "vegetables" has "stock" set to 7
                    Then the fake store "fruit" should have "stock" equal to 5
                    And the fake store "vegetables" should have "stock" equal to 7
                """);

        assertThat(run("local").successful()).isTrue();
    }

    @Test
    void configurationValuesAreResolvedFromEnvironmentVariables() throws IOException {
        sources("""
                profiles:
                  local:
                    sources:
                      fruit:
                        type: fake
                        config:
                          default: ${APPLES_IN_STOCK}
                """);
        feature("""
                Feature: Store
                  Scenario: Reading a key that was never set
                    Then the fake store "fruit" should have "apples" equal to 12
                """);

        ExecutionResult result = Engine.run(
                new ExecutionRequest(workspace, "local", Map.of("APPLES_IN_STOCK", "12")));

        assertThat(result.successful()).isTrue();
    }

    @Test
    void missingEnvironmentVariableIsReportedByName() throws IOException {
        sources("""
                profiles:
                  local:
                    sources:
                      fruit:
                        type: fake
                        config:
                          default: ${APPLES_IN_STOCK}
                """);
        feature(PASSING_FEATURE);

        assertThatThrownBy(() -> run("local"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Invalid sources.yaml: profiles.local.sources.fruit.config.default: "
                        + "environment variable 'APPLES_IN_STOCK' is not set");
    }

    @Test
    void environmentVariablesOfOtherProfilesAreNotNeeded() throws IOException {
        sources("""
                profiles:
                  local:
                    sources:
                      fruit:
                        type: fake
                  staging:
                    sources:
                      fruit:
                        type: fake
                        config:
                          default: ${APPLES_IN_STOCK}
                """);
        feature(PASSING_FEATURE);

        assertThat(run("local").successful()).isTrue();
    }

    @Test
    void stepCitingASourceMissingFromTheProfileFails() throws IOException {
        sources("""
                profiles:
                  local:
                    sources:
                      fruit:
                        type: fake
                  staging:
                    sources:
                      vegetables:
                        type: fake
                """);
        feature("""
                Feature: Store
                  Scenario: Unknown source
                    Given the fake store "vegetables" has "leeks" set to 5
                    Then the fake store "fruit" should have "apples" equal to 5
                """);

        ScenarioResult scenario = run("local").scenarios().getFirst();

        assertThat(scenario.steps()).extracting(StepResult::status, StepResult::error)
                .containsExactly(
                        tuple(Status.FAILED, "Source 'vegetables' is not defined in profile 'local'"),
                        tuple(Status.SKIPPED, null));
    }

    @Test
    void stepCitingASourceOfAnotherConnectorFails() throws IOException {
        sources("""
                profiles:
                  local:
                    sources:
                      orders:
                        type: strict
                        config:
                          url: http://localhost
                """);
        feature("""
                Feature: Store
                  Scenario: Source of another type
                    Then the fake store "orders" should have "apples" equal to 5
                """);

        assertThat(run("local").scenarios().getFirst().steps().getFirst().error())
                .isEqualTo("Source 'orders' of profile 'local' is of type 'strict', "
                        + "but this phrase acts on sources of type 'fake'");
    }

    @Test
    void writeStepOnAReadOnlySourceFailsWithoutBeingExecuted() throws IOException {
        sources("""
                profiles:
                  local:
                    sources:
                      fruit:
                        type: fake
                        readOnly: true
                        config:
                          default: 4
                """);
        feature("""
                Feature: Store
                  Scenario: Writing
                    Given the fake store "fruit" has "apples" set to 5

                  Scenario: Reading
                    Then the fake store "fruit" should have "apples" equal to 4
                """);

        ExecutionResult result = run("local");

        assertThat(result.scenarios()).extracting(ScenarioResult::name, ScenarioResult::status)
                .containsExactly(tuple("Writing", Status.FAILED), tuple("Reading", Status.PASSED));
        assertThat(result.scenarios().getFirst().steps().getFirst().error())
                .isEqualTo("Source 'fruit' of profile 'local' is read-only: "
                        + "this step writes to it, so it was not executed");
    }

    @Test
    void sourceTypeWithoutAnInstalledConnectorIsRejected() throws IOException {
        sources("""
                profiles:
                  local:
                    sources:
                      orders:
                        type: postgresql
                """);
        feature(PASSING_FEATURE);

        assertThatThrownBy(() -> run("local"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Invalid sources.yaml: profiles.local.sources.orders.type: "
                        + "no connector is installed for source type 'postgresql'; installed: fake, recording, strict");
    }

    @Test
    void parameterTheConnectorDoesNotDeclareIsRejected() throws IOException {
        sources("""
                profiles:
                  local:
                    sources:
                      fruit:
                        type: fake
                        config:
                          colour: red
                """);
        feature(PASSING_FEATURE);

        assertThatThrownBy(() -> run("local"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Invalid sources.yaml: profiles.local.sources.fruit.config.colour: "
                        + "not a parameter of connector 'fake'; allowed: default");
    }

    @Test
    void parameterOfTheWrongTypeIsRejected() throws IOException {
        sources("""
                profiles:
                  local:
                    sources:
                      fruit:
                        type: fake
                        config:
                          default: plenty
                """);
        feature(PASSING_FEATURE);

        assertThatThrownBy(() -> run("local"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Invalid sources.yaml: profiles.local.sources.fruit.config.default: "
                        + "expected an integer but was 'plenty'");
    }

    @Test
    void unquotedNumberForATextParameterIsRejectedInsteadOfBeingReinterpreted() throws IOException {
        sources("""
                profiles:
                  local:
                    sources:
                      orders:
                        type: strict
                        config:
                          url: 0123
                """);
        feature(PASSING_FEATURE);

        assertThatThrownBy(() -> run("local"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Invalid sources.yaml: profiles.local.sources.orders.config.url: "
                        + "expected a text (write it between quotes) but was 83");
    }

    @Test
    void missingRequiredParameterIsRejected() throws IOException {
        sources("""
                profiles:
                  local:
                    sources:
                      orders:
                        type: strict
                        config:
                          secure: true
                """);
        feature(PASSING_FEATURE);

        assertThatThrownBy(() -> run("local"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Invalid sources.yaml: profiles.local.sources.orders.config.url: "
                        + "required by connector 'strict'");
    }

    @Test
    void malformedConfigurationIsRejected() throws IOException {
        sources("""
                profiles:
                  local:
                    sources: [fruit
                """);
        feature(PASSING_FEATURE);

        assertThatThrownBy(() -> run("local"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("sources.yaml");
    }

    @Test
    void misshapenConfigurationIsRejectedEvenOutsideTheChosenProfile() throws IOException {
        sources("""
                profiles:
                  local:
                    sources:
                      fruit:
                        type: fake
                  staging:
                    sources:
                      fruit:
                        kind: fake
                """);
        feature(PASSING_FEATURE);

        assertThatThrownBy(() -> run("local"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Invalid sources.yaml: profiles.staging.sources.fruit.kind: "
                        + "unknown property; allowed: type, image, readOnly, mask, config");
    }

    @Test
    void profileThatIsNotDeclaredIsRejected() throws IOException {
        sources("""
                profiles:
                  local:
                    sources:
                      fruit:
                        type: fake
                  staging:
                """);
        feature(PASSING_FEATURE);

        assertThatThrownBy(() -> run("production"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Profile 'production' is not declared in sources.yaml, which declares: local, staging");
        assertThatThrownBy(() -> run(null))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("No profile was requested; sources.yaml declares: local, staging");
    }

    @Test
    void workspaceWithoutConfigurationHasNoSources() throws IOException {
        feature(PASSING_FEATURE);

        assertThat(run(null).scenarios().getFirst().steps().getFirst().error())
                .isEqualTo("Source 'fruit' is not defined because the workspace has no sources.yaml");
        assertThatThrownBy(() -> run("local"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Profile 'local' was requested, but there is no sources.yaml in workspace");
    }

    private ExecutionResult run(String profile) {
        return Engine.run(new ExecutionRequest(workspace, profile, Map.of()));
    }

    private void sources(String content) throws IOException {
        Files.writeString(workspace.resolve("sources.yaml"), content);
    }

    private void feature(String content) throws IOException {
        Path file = workspace.resolve("features/store.feature");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
