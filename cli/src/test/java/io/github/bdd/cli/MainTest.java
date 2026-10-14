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
        assertThat(outputWithoutReportsLine()).isEqualToNormalizingNewlines("""
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
        assertThat(outputWithoutReportsLine()).isEqualToNormalizingNewlines("""
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

    @Test
    void pathAndTagsSelectWhatToRunAndReportsAreWritten() throws IOException {
        feature("""
                Feature: Store
                  @smoke
                  Scenario: Fast
                    Given the fake store "fruit" has "apples" set to 5

                  @wip
                  Scenario: Slow
                    Given the fake store "fruit" has "pears" set to 1
                """);

        assertThat(run("run", workspace.toString(), "--profile", "local", "--tags", "not @wip")).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Fast").doesNotContain("Slow")
                .contains("1 scenario: 1 passed").contains("Reports: ");
        out.reset();

        assertThat(run("run", workspace.toString(), "--profile", "local",
                "--path", "features/store.feature:7")).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Slow").doesNotContain("Fast");

        try (var runs = Files.list(workspace.resolve("reports"))) {
            assertThat(runs).hasSize(2).allSatisfy(folder -> assertThat(folder.resolve("summary.json")).exists());
        }
    }

    @Test
    void selectionMatchingNoScenarioIsReportedWithADistinctExitCode() throws IOException {
        feature("""
                Feature: Store
                  @smoke
                  Scenario: Fast
                    Given the fake store "fruit" has "apples" set to 5
                """);

        assertThat(run("run", workspace.toString(), "--profile", "local", "--tags", "@nothing")).isEqualTo(3);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("No scenario matches the selection");
    }

    @Test
    void invalidSelectionExitsWithError() throws IOException {
        feature("""
                Feature: Store
                  Scenario: Fast
                    Given the fake store "fruit" has "apples" set to 5
                """);

        assertThat(run("run", workspace.toString(), "--profile", "local", "--path", "features/missing.feature"))
                .isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("does not exist");
        assertThat(run("run", workspace.toString(), "--profile", "local", "--tags", "@a and")).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Invalid tag expression");
        assertThat(run("run", workspace.toString(), "--tags")).isEqualTo(2);
    }

    @Test
    void pendingScenarioIsShownAndDoesNotFailTheExecutionByDefault() throws IOException {
        Files.writeString(workspace.resolve("catalog.yaml"), """
                pending:
                  - id: store.wipe
                    phrase: "the store {name} is wiped"
                """);
        feature("""
                Feature: Store
                  Scenario: Wipe
                    Given the fake store "fruit" has "apples" set to 5
                    When the store "fruit" is wiped
                    Then the fake store "fruit" should have "apples" equal to 5
                """);

        int exitCode = run("run", workspace.toString(), "--profile", "local");

        assertThat(exitCode).isZero();
        assertThat(outputWithoutReportsLine()).isEqualToNormalizingNewlines("""
                PENDING    features/store.feature:2 Wipe
                  PASSED     Given the fake store "fruit" has "apples" set to 5
                  PENDING    When the store "fruit" is wiped
                             The phrase 'store.wipe' has no implementation yet
                  SKIPPED    Then the fake store "fruit" should have "apples" equal to 5

                1 scenario: 1 pending
                """);
    }

    @Test
    void failOnPendingMakesPendingScenariosFailTheExecution() throws IOException {
        Files.writeString(workspace.resolve("catalog.yaml"), """
                pending:
                  - id: store.wipe
                    phrase: "the store {name} is wiped"
                """);
        feature("""
                Feature: Store
                  Scenario: Wipe
                    When the store "fruit" is wiped
                """);

        assertThat(run("run", workspace.toString(), "--profile", "local", "--fail-on-pending")).isEqualTo(1);
    }

    private String outputWithoutReportsLine() {
        return out.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> !line.startsWith("Reports: "))
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
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
