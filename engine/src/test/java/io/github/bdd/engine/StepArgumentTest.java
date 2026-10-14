package io.github.bdd.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What an action receives from the table, doc string and file of a step. */
class StepArgumentTest {

    @TempDir
    Path workspace;

    @BeforeEach
    void declareTheSources() throws IOException {
        RecordingConnector.EVENTS.clear();
        Files.writeString(workspace.resolve("sources.yaml"), """
                profiles:
                  local:
                    sources:
                      db:
                        type: recording
                        config:
                          url: here
                      shared:
                        type: recording
                        readOnly: true
                        config:
                          url: there
                """);
    }

    @Test
    void tableOfTheStepIsGivenToTheAction() throws IOException {
        feature("""
                Feature: Arguments
                  Scenario: A table
                    Given the url of "db" is recorded
                    When these rows are recorded on "db"
                      | id | name |
                      | 1  | Ada  |
                      | 2  |      |
                """);

        assertThat(run().successful()).isTrue();
        assertThat(RecordingConnector.EVENTS).containsExactly("url here", "table [[id, name], [1, Ada], [2, ]]");
    }

    @Test
    void docStringOfTheStepIsGivenToTheActionWithItsVariablesResolved() throws IOException {
        feature("""
                Feature: Arguments
                  Scenario: A text
                    When this text is recorded on "db"
                      \"\"\"
                      select ${env:WHO};
                      \"\"\"
                """);

        assertThat(run().successful()).isTrue();
        assertThat(RecordingConnector.EVENTS).containsExactly("text select Ada;");
    }

    @Test
    void filesAreReadRelativeToTheWorkspace() throws IOException {
        Files.createDirectories(workspace.resolve("data"));
        Files.writeString(workspace.resolve("data/rows.csv"), "id,name\n1,Ada\n");
        feature("""
                Feature: Arguments
                  Scenario: A file
                    When the file "data/rows.csv" is recorded on "db"
                """);

        assertThat(run().successful()).isTrue();
        assertThat(RecordingConnector.EVENTS).containsExactly("file data/rows.csv id,name\n1,Ada");
    }

    @Test
    void filesOutsideTheWorkspaceOrMissingFailTheStep() throws IOException {
        Files.writeString(workspace.getParent().resolve("outside.csv"), "secret");
        feature("""
                Feature: Arguments
                  Scenario: Outside
                    When the file "../outside.csv" is recorded on "db"

                  Scenario: Missing
                    When the file "data/missing.csv" is recorded on "db"
                """);

        var scenarios = run().scenarios();

        assertThat(scenarios.get(0).steps().getFirst().error())
                .isEqualTo("File '../outside.csv' is not inside the workspace");
        assertThat(scenarios.get(1).steps().getFirst().error())
                .isEqualTo("File 'data/missing.csv' does not exist in the workspace");
        assertThat(RecordingConnector.EVENTS).isEmpty();
    }

    @Test
    void stepWithoutTheTableTheActionNeedsFails() throws IOException {
        feature("""
                Feature: Arguments
                  Scenario: No table
                    When these rows are recorded on "db"

                  Scenario: A doc string instead of a table
                    When these rows are recorded on "db"
                      \"\"\"
                      id
                      \"\"\"

                  Scenario: A table instead of a doc string
                    When this text is recorded on "db"
                      | id |
                """);

        var scenarios = run().scenarios();

        assertThat(scenarios.get(0).steps().getFirst().error()).isEqualTo("This step needs a table");
        assertThat(scenarios.get(1).steps().getFirst().error()).isEqualTo("This step needs a table");
        assertThat(scenarios.get(2).steps().getFirst().error()).isEqualTo("This step needs a doc string");
    }

    @Test
    void writesWithATableAreBlockedOnAReadOnlySource() throws IOException {
        feature("""
                Feature: Arguments
                  Scenario: Read only
                    When these rows are recorded on "shared"
                      | id |
                      | 1  |
                """);

        assertThat(run().scenarios().getFirst().steps().getFirst().error())
                .contains("is read-only: this step writes to it, so it was not executed");
        assertThat(RecordingConnector.EVENTS).isEmpty();
    }

    private ExecutionResult run() {
        return Engine.run(new ExecutionRequest(workspace, "local", Map.of("WHO", "Ada")));
    }

    private void feature(String content) throws IOException {
        Path file = workspace.resolve("features/arguments.feature");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
