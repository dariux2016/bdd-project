package io.github.bdd.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MainTest {

    @TempDir
    Path workspace;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void declareTheFruitSource() throws IOException {
        Files.writeString(workspace.resolve("sources.yaml"), """
                profiles:
                  local:
                    sources:
                      fruit:
                        type: fake
                """);
    }

    @Test
    void runPrintsTheOutcomeAndExitsWithZeroWhenEveryScenarioPasses() throws IOException {
        feature("""
                Feature: Store
                  Scenario: Adding to a key
                    Given the fake store "fruit" has "apples" set to 5
                    When 3 is added to "apples" in the fake store "fruit"
                    Then the fake store "fruit" should have "apples" equal to 8
                """);

        int exitCode = run("run", workspace.toString(), "--profile", "local");

        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualToNormalizingNewlines("""
                PASSED     features/store.feature:2 Adding to a key
                  PASSED     Given the fake store "fruit" has "apples" set to 5
                  PASSED     When 3 is added to "apples" in the fake store "fruit"
                  PASSED     Then the fake store "fruit" should have "apples" equal to 8

                1 scenario: 1 passed
                """);
    }

    @Test
    void runExitsWithNonZeroWhenAScenarioDoesNotPass() throws IOException {
        feature("""
                Feature: Store
                  Scenario: Wrong expectation
                    Given the fake store "fruit" has "apples" set to 5
                    Then the fake store "fruit" should have "apples" equal to 6
                    And the fake store "fruit" should have "apples" equal to 5

                  Scenario: Unknown phrase
                    When the apples are eaten

                  Scenario: Setting a key
                    Given the fake store "fruit" has "pears" set to 1
                """);

        int exitCode = run("run", "--profile", "local", workspace.toString());

        assertThat(exitCode).isEqualTo(1);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualToNormalizingNewlines("""
                FAILED     features/store.feature:2 Wrong expectation
                  PASSED     Given the fake store "fruit" has "apples" set to 5
                  FAILED     Then the fake store "fruit" should have "apples" equal to 6
                             Expected "apples" to equal 6 but was 5
                  SKIPPED    And the fake store "fruit" should have "apples" equal to 5
                UNDEFINED  features/store.feature:7 Unknown phrase
                  UNDEFINED  When the apples are eaten
                PASSED     features/store.feature:10 Setting a key
                  PASSED     Given the fake store "fruit" has "pears" set to 1

                3 scenarios: 1 passed, 1 failed, 1 undefined
                """);
    }

    @Test
    void invalidInvocationExitsWithUsageError() {
        assertThat(run("launch")).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Usage: run [workspace] [--profile <name>]");
        assertThat(run("run", workspace.toString(), "--profile")).isEqualTo(2);
        assertThat(run("run", "--profile", "--profile", workspace.toString())).isEqualTo(2);
        assertThat(run("run", workspace.toString(), "--verbose")).isEqualTo(2);

        assertThat(run("run", workspace.toString(), "--profile", "local")).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("No 'features' directory in workspace");
    }

    @Test
    void unparsableFeatureExitsWithErrorInsteadOfLookingLikeAFailedScenario() throws IOException {
        feature("""
                Given a step outside of any feature
                """);

        assertThat(run("run", workspace.toString(), "--profile", "local")).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("store.feature");
    }

    @Test
    void invalidConfigurationExitsWithErrorBeforeRunningAnyScenario() throws IOException {
        feature("""
                Feature: Store
                  Scenario: Setting a key
                    Given the fake store "fruit" has "apples" set to 5
                """);

        assertThat(run("run", workspace.toString(), "--profile", "staging")).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Profile 'staging' is not declared in sources.yaml");
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    private int run(String... args) {
        return Main.execute(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private void feature(String content) throws IOException {
        Path file = workspace.resolve("features/store.feature");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
