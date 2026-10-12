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
import static org.assertj.core.api.Assertions.tuple;

class EngineTest {

    @TempDir
    Path workspace;

    @BeforeEach
    void declareTheFruitSource() throws IOException {
        sources("""
                profiles:
                  local:
                    sources:
                      fruit:
                        type: fake
                """);
    }

    @Test
    void featureUsingConnectorPhrasesPasses() throws IOException {
        feature("store.feature", """
                Feature: Store
                  Scenario: Adding to a key
                    Given the fake store "fruit" has "apples" set to 5
                    When 3 is added to "apples" in the fake store "fruit"
                    Then the fake store "fruit" should have "apples" equal to 8
                """);

        ExecutionResult result = Engine.run(new ExecutionRequest(workspace, "local", Map.of()));

        assertThat(result.successful()).isTrue();
        assertThat(result.scenarios()).singleElement().satisfies(scenario -> {
            assertThat(scenario.feature()).isEqualTo("features/store.feature");
            assertThat(scenario.name()).isEqualTo("Adding to a key");
            assertThat(scenario.status()).isEqualTo(Status.PASSED);
            assertThat(scenario.steps()).extracting(StepResult::keyword, StepResult::text, StepResult::status)
                    .containsExactly(
                            tuple(
                                    "Given", "the fake store \"fruit\" has \"apples\" set to 5", Status.PASSED),
                            tuple(
                                    "When", "3 is added to \"apples\" in the fake store \"fruit\"", Status.PASSED),
                            tuple(
                                    "Then", "the fake store \"fruit\" should have \"apples\" equal to 8", Status.PASSED));
        });
    }

    @Test
    void unsatisfiedVerificationFailsTheStepAndSkipsTheFollowingOnes() throws IOException {
        feature("store.feature", """
                Feature: Store
                  Scenario: Wrong expectation
                    Given the fake store "fruit" has "apples" set to 5
                    Then the fake store "fruit" should have "apples" equal to 6
                    And the fake store "fruit" should have "apples" equal to 5
                """);

        ExecutionResult result = Engine.run(new ExecutionRequest(workspace, "local", Map.of()));

        assertThat(result.successful()).isFalse();
        ScenarioResult scenario = result.scenarios().getFirst();
        assertThat(scenario.status()).isEqualTo(Status.FAILED);
        assertThat(scenario.steps()).extracting(StepResult::status)
                .containsExactly(Status.PASSED, Status.FAILED, Status.SKIPPED);
        assertThat(scenario.steps().get(1).error()).isEqualTo("Expected \"apples\" to equal 6 but was 5");
    }

    @Test
    void stepMatchingNoPhraseIsUndefined() throws IOException {
        feature("store.feature", """
                Feature: Store
                  Scenario: Unknown phrase
                    Given the fake store "fruit" has "apples" set to 5
                    When the apples are eaten
                    Then the fake store "fruit" should have "apples" equal to 5
                """);

        ExecutionResult result = Engine.run(new ExecutionRequest(workspace, "local", Map.of()));

        assertThat(result.successful()).isFalse();
        ScenarioResult scenario = result.scenarios().getFirst();
        assertThat(scenario.status()).isEqualTo(Status.UNDEFINED);
        assertThat(scenario.steps()).extracting(StepResult::text, StepResult::status)
                .containsExactly(
                        tuple("the fake store \"fruit\" has \"apples\" set to 5", Status.PASSED),
                        tuple("the apples are eaten", Status.UNDEFINED),
                        tuple("the fake store \"fruit\" should have \"apples\" equal to 5", Status.SKIPPED));
    }

    @Test
    void scenarioOutlineRunsOncePerExampleRow() throws IOException {
        feature("store.feature", """
                Feature: Store
                  Scenario Outline: Adding to a key
                    Given the fake store "fruit" has "<key>" set to <start>
                    When <amount> is added to "<key>" in the fake store "fruit"
                    Then the fake store "fruit" should have "<key>" equal to <total>

                    Examples:
                      | key    | start | amount | total |
                      | apples | 5     | 3      | 8     |
                      | pears  | 1     | 1      | 3     |
                """);

        ExecutionResult result = Engine.run(new ExecutionRequest(workspace, "local", Map.of()));

        assertThat(result.scenarios()).extracting(ScenarioResult::line, ScenarioResult::status)
                .containsExactly(tuple(9, Status.PASSED), tuple(10, Status.FAILED));
        assertThat(result.scenarios().get(1).steps()).last()
                .extracting(StepResult::text, StepResult::error)
                .containsExactly(
                        "the fake store \"fruit\" should have \"pears\" equal to 3",
                        "Expected \"pears\" to equal 3 but was 2");
    }

    @Test
    void featuresInSubfoldersAreExecuted() throws IOException {
        feature("fruit/store.feature", """
                Feature: Store
                  Scenario: Setting a key
                    Given the fake store "fruit" has "apples" set to 5
                """);

        ExecutionResult result = Engine.run(new ExecutionRequest(workspace, "local", Map.of()));

        assertThat(result.scenarios()).extracting(ScenarioResult::feature, ScenarioResult::status)
                .containsExactly(tuple("features/fruit/store.feature", Status.PASSED));
    }

    @Test
    void workspaceWithoutFeaturesDirectoryIsRejected() {
        assertThatThrownBy(() -> Engine.run(new ExecutionRequest(workspace, "local", Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No 'features' directory in workspace");
    }

    private void sources(String content) throws IOException {
        Files.writeString(workspace.resolve("sources.yaml"), content);
    }

    private void feature(String name, String content) throws IOException {
        Path file = workspace.resolve("features").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
