package io.github.bdd.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportsTest {

    @TempDir
    Path workspace;

    @BeforeEach
    void workspaceWithTwoFeatures() throws IOException {
        write("sources.yaml", """
                profiles:
                  local:
                    sources:
                      fruit:
                        type: fake
                """);
        write("features/a/one.feature", """
                Feature: One
                  @smoke
                  Scenario: First
                    Given the fake store "fruit" has "apples" set to 5

                  @wip @smoke
                  Scenario: Second
                    Given the fake store "fruit" has "apples" set to 6
                """);
        write("features/b/two.feature", """
                Feature: Two
                  Scenario: Third
                    Given the fake store "fruit" has "pears" set to 1
                    Then the fake store "fruit" should have "pears" equal to 2
                """);
    }

    @Test
    void wholeWorkspaceRunsEverything() {
        assertThat(names(Selection.ALL)).containsExactly("First", "Second", "Third");
    }

    @Test
    void aFolderAFileAndASingleScenarioCanBeSelected() {
        assertThat(names(new Selection(List.of("features/a"), null))).containsExactly("First", "Second");
        assertThat(names(new Selection(List.of("features/b/two.feature"), null))).containsExactly("Third");
        assertThat(names(new Selection(List.of("features/a/one.feature:7"), null))).containsExactly("Second");
        assertThat(names(new Selection(List.of("features/a/one.feature:3", "features/b"), null)))
                .containsExactly("First", "Third");
    }

    @Test
    void tagExpressionsSupportNegationAndCombination() {
        assertThat(names(new Selection(List.of(), "@smoke"))).containsExactly("First", "Second");
        assertThat(names(new Selection(List.of(), "@smoke and not @wip"))).containsExactly("First");
        assertThat(names(new Selection(List.of(), "@wip or not @smoke"))).containsExactly("Second", "Third");
        assertThat(names(new Selection(List.of("features/a"), "not @smoke"))).isEmpty();
    }

    @Test
    void selectionMatchingNothingIsExplicit() throws IOException {
        ExecutionResult result = run(new Selection(List.of(), "@nothing"));

        assertThat(result.noScenarioSelected()).isTrue();
        assertThat(result.successful()).isFalse();
        JsonNode summary = summary(result);
        assertThat(summary.get("noScenarioSelected").asBoolean()).isTrue();
        assertThat(summary.get("counts").get("total").asInt()).isZero();
    }

    @Test
    void invalidSelectionIsRejected() {
        assertThatThrownBy(() -> run(new Selection(List.of("features/none.feature"), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not exist");
        assertThatThrownBy(() -> run(new Selection(List.of("sources.yaml"), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not inside the features");
        assertThatThrownBy(() -> run(new Selection(List.of("features/../sources.yaml"), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(new Selection(List.of(), "@a and")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid tag expression");
    }

    @Test
    void everyExecutionWritesReportsAndASummary() throws IOException {
        ExecutionResult result = run(new Selection(List.of("features/b"), "not @wip"));

        Path folder = result.reports();
        assertThat(folder.getParent()).isEqualTo(workspace.toAbsolutePath().normalize().resolve("reports"));
        assertThat(folder.getFileName().toString()).matches("\\d{8}-\\d{6}-\\d{3}(-\\d+)?");
        assertThat(folder.resolve("cucumber.json")).isNotEmptyFile();
        assertThat(folder.resolve("junit.xml")).isNotEmptyFile();
        assertThat(folder.resolve("report.html")).isNotEmptyFile();
        assertThat(Files.readString(folder.resolve("cucumber.json"))).contains("Third");

        JsonNode summary = summary(result);
        assertThat(summary.get("profile").asText()).isEqualTo("local");
        assertThat(summary.get("selection").get("paths")).hasSize(1);
        assertThat(summary.get("selection").get("tags").asText()).isEqualTo("not @wip");
        assertThat(summary.get("durationMillis").asLong()).isNotNegative();
        assertThat(summary.get("counts").get("total").asInt()).isEqualTo(1);
        assertThat(summary.get("counts").get("failed").asInt()).isEqualTo(1);
        assertThat(summary.get("counts").get("passed").asInt()).isZero();
        assertThat(summary.get("successful").asBoolean()).isFalse();
    }

    @Test
    void onlyTheLastExecutionsAreKept() throws IOException {
        write("settings.yaml", """
                reports:
                  keep: 2
                """);
        Path unrelated = workspace.resolve("reports/notes");
        Files.createDirectories(unrelated);
        Files.createDirectories(workspace.resolve("reports/20000101-000000-000"));

        Path first = run(Selection.ALL).reports();
        Path second = run(Selection.ALL).reports();
        Path third = run(Selection.ALL).reports();

        assertThat(first).doesNotExist();
        assertThat(second).isDirectory();
        assertThat(third).isDirectory();
        try (Stream<Path> folders = Files.list(workspace.resolve("reports"))) {
            assertThat(folders.map(path -> path.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder("notes", second.getFileName().toString(), third.getFileName().toString());
        }
    }

    @Test
    void retentionMustBeAPositiveNumber() throws IOException {
        write("settings.yaml", """
                reports:
                  keep: 0
                """);

        assertThatThrownBy(() -> run(Selection.ALL)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("reports.keep");
    }

    private List<String> names(Selection selection) {
        return run(selection).scenarios().stream().map(ScenarioResult::name).sorted().toList();
    }

    private ExecutionResult run(Selection selection) {
        return Engine.run(new ExecutionRequest(workspace, "local", Map.of(), selection));
    }

    private JsonNode summary(ExecutionResult result) throws IOException {
        return new ObjectMapper().readTree(result.reports().resolve("summary.json").toFile());
    }

    private void write(String name, String content) throws IOException {
        Path file = workspace.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
