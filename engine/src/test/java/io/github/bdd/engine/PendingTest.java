package io.github.bdd.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingTest {

    private static final String REBUILD = """
            pending:
              - id: reports.rebuild
                phrase: "the report {name} is rebuilt"
            """;

    @TempDir
    Path workspace;

    @Test
    void stepOnPendingPhraseIsPendingAndTheFollowingOnesAreSkipped() throws IOException {
        Files.writeString(workspace.resolve("catalog.yaml"), REBUILD);

        ExecutionResult result = run("""
                Feature: Reports
                  Scenario: Rebuild
                    Given the fake store "s" has "k" set to 3
                    When the report "daily" is rebuilt
                    Then the fake store "s" should have "k" equal to 3
                """);

        ScenarioResult scenario = result.scenarios().getFirst();
        assertThat(scenario.status()).isEqualTo(Status.PENDING);
        assertThat(scenario.steps()).extracting(StepResult::status)
                .containsExactly(Status.PASSED, Status.PENDING, Status.SKIPPED);
        assertThat(scenario.steps().get(1).error()).contains("has no implementation yet");
    }

    @Test
    void pendingIsDistinctFromUndefined() throws IOException {
        Files.writeString(workspace.resolve("catalog.yaml"), REBUILD);

        ExecutionResult result = run("""
                Feature: Reports
                  Scenario: Pending
                    When the report "daily" is rebuilt
                  Scenario: Undefined
                    When nobody knows this
                """);

        assertThat(result.scenarios()).extracting(ScenarioResult::status)
                .containsExactly(Status.PENDING, Status.UNDEFINED);
    }

    @Test
    void pendingStepAcceptsATable() throws IOException {
        Files.writeString(workspace.resolve("catalog.yaml"), REBUILD);

        ExecutionResult result = run("""
                Feature: Reports
                  Scenario: With data
                    When the report "daily" is rebuilt
                      | a | b |
                      | 1 | 2 |
                """);

        assertThat(result.scenarios().getFirst().status()).isEqualTo(Status.PENDING);
    }

    @Test
    void pendingScenarioIsCountedInTheSummary() throws IOException {
        Files.writeString(workspace.resolve("catalog.yaml"), REBUILD);

        ExecutionResult result = run("""
                Feature: Reports
                  Scenario: Rebuild
                    When the report "daily" is rebuilt
                """);

        assertThat(Files.readString(result.reports().resolve("summary.json")))
                .contains("\"pending\" : 1")
                .contains("\"successful\" : true");
    }

    @Test
    void pendingDoesNotFailTheExecutionUnlessAskedTo() throws IOException {
        Files.writeString(workspace.resolve("catalog.yaml"), REBUILD);

        ExecutionResult result = run("""
                Feature: Reports
                  Scenario: Done
                    Given the fake store "s" has "k" set to 3
                  Scenario: Rebuild
                    When the report "daily" is rebuilt
                """);

        assertThat(result.hasPending()).isTrue();
        assertThat(result.successful()).isTrue();
        assertThat(result.successful(false)).isTrue();
        assertThat(result.successful(true)).isFalse();
    }

    @Test
    void failedScenarioIsNotSuccessfulWhateverThePendingPolicy() throws IOException {
        Files.writeString(workspace.resolve("catalog.yaml"), REBUILD);

        ExecutionResult result = run("""
                Feature: Reports
                  Scenario: Wrong
                    Given the fake store "s" has "k" set to 3
                    Then the fake store "s" should have "k" equal to 4
                  Scenario: Rebuild
                    When the report "daily" is rebuilt
                """);

        assertThat(result.successful(false)).isFalse();
    }

    @Test
    void pendingPhraseBecomesActiveWhenAConnectorDeclaresItsId() throws IOException {
        // fake.put is declared by the fake connector, with another wording
        Files.writeString(workspace.resolve("catalog.yaml"), """
                pending:
                  - id: fake.put
                    phrase: "{key} is stored in the fake store {source} as {value}"
                """);

        ExecutionResult result = run("""
                Feature: Active
                  Scenario: Stored
                    Given "k" is stored in the fake store "s" as 7
                    Then the fake store "s" should have "k" equal to 7
                """);

        assertThat(result.scenarios().getFirst().status()).isEqualTo(Status.PASSED);
        assertThat(Engine.catalog(workspace)).filteredOn(entry -> entry.pattern().startsWith("{key} is stored"))
                .singleElement().satisfies(entry -> assertThat(entry.target()).isEqualTo("fake.put"));
    }

    @Test
    void pendingPhraseWithTheWordingOfTheConnectorBecomesTheConnectorsOne() throws IOException {
        Files.writeString(workspace.resolve("catalog.yaml"), """
                pending:
                  - id: fake.put
                    phrase: "the fake store {source} has {key} set to {value}"
                """);

        assertThat(Engine.catalog(workspace))
                .filteredOn(entry -> entry.pattern().equals("the fake store {source} has {key} set to {value}"))
                .singleElement().satisfies(entry -> assertThat(entry.origin()).isEqualTo("connector:fake"));
    }

    @Test
    void catalogListsPendingPhrasesWithTheirId() throws IOException {
        Files.writeString(workspace.resolve("catalog.yaml"), REBUILD);

        List<CatalogEntry> catalog = Engine.catalog(workspace);

        assertThat(catalog).filteredOn(entry -> entry.target().startsWith("pending"))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.pattern()).isEqualTo("the report {name} is rebuilt");
                    assertThat(entry.target()).isEqualTo("pending:reports.rebuild");
                    assertThat(entry.origin()).isEqualTo("workspace");
                    assertThat(entry.parameters()).extracting(parameter -> parameter.name())
                            .containsExactly("name");
                });
    }

    @Test
    void italianPendingPhraseIsRecognized() throws IOException {
        Files.writeString(workspace.resolve("catalog.yaml"), """
                pending:
                  - id: reports.rebuild
                    phrase: "il rapporto {name} viene ricostruito"
                    language: it
                """);

        ExecutionResult result = run("""
                # language: it
                Funzionalità: Rapporti
                  Scenario: Ricostruzione
                    Quando il rapporto "giornaliero" viene ricostruito
                """);

        assertThat(result.scenarios().getFirst().status()).isEqualTo(Status.PENDING);
    }

    @Test
    void duplicatedIdIsRefused() throws IOException {
        Files.writeString(workspace.resolve("catalog.yaml"), REBUILD + """
                  - id: reports.rebuild
                    phrase: "the report {name} is built again"
                """);

        assertThatThrownBy(() -> Engine.catalog(workspace))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("pending[1].id")
                .hasMessageContaining("reports.rebuild");
    }

    @Test
    void pendingPhraseWithoutIdIsRefused() throws IOException {
        Files.writeString(workspace.resolve("catalog.yaml"), """
                pending:
                  - phrase: "the report {name} is rebuilt"
                """);

        assertThatThrownBy(() -> Engine.catalog(workspace))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("pending[0].id");
    }

    @Test
    void pendingPhraseAmbiguousWithAnotherIsRefused() throws IOException {
        Files.writeString(workspace.resolve("catalog.yaml"), REBUILD + """
                  - id: reports.rebuild-again
                    phrase: "the report {title} is rebuilt"
                """);

        assertThatThrownBy(() -> Engine.catalog(workspace))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Ambiguous");
    }

    @Test
    void activatedPhraseWithOtherParametersIsRefused() throws IOException {
        Files.writeString(workspace.resolve("catalog.yaml"), """
                pending:
                  - id: fake.put
                    phrase: "{key} is stored in the fake store {source}"
                """);

        assertThatThrownBy(() -> Engine.catalog(workspace))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("pending[0].phrase")
                .hasMessageContaining("fake.put");
    }

    private ExecutionResult run(String feature) throws IOException {
        Files.writeString(workspace.resolve("sources.yaml"), """
                profiles:
                  local:
                    sources:
                      s:
                        type: fake
                """);
        Path file = workspace.resolve("features/pending.feature");
        Files.createDirectories(file.getParent());
        Files.writeString(file, feature);
        return Engine.run(new ExecutionRequest(workspace, "local", Map.of()));
    }
}
