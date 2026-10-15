package io.github.bdd.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

class PendingPhrasesTest {

    @TempDir
    Path workspace;

    @BeforeEach
    void declareTheShopSource() throws IOException {
        write("sources.yaml", """
                profiles:
                  local:
                    sources:
                      shop:
                        type: fake
                """);
        write("settings.yaml", """
                verification:
                  timeout: 0ms
                """);
    }

    @Test
    void stepOfAPendingPhraseIsPendingAndTheStepsAfterItAreSkipped() throws IOException {
        catalog("""
                pending:
                  - id: shop.archive
                    phrase: "the order {order} is archived"
                """);
        ExecutionResult result = run("""
                Feature: Pending
                  Scenario: Archive
                    Given the fake store "shop" has "apples" set to 1
                    When the order "A-1" is archived
                    Then the fake store "shop" should have "apples" equal to 1
                """);

        ScenarioResult scenario = result.scenarios().getFirst();
        assertThat(scenario.status()).isEqualTo(Status.PENDING);
        assertThat(scenario.steps()).extracting(StepResult::text, StepResult::status).containsExactly(
                tuple("the fake store \"shop\" has \"apples\" set to 1", Status.PASSED),
                tuple("the order \"A-1\" is archived", Status.PENDING),
                tuple("the fake store \"shop\" should have \"apples\" equal to 1", Status.SKIPPED));
    }

    @Test
    void pendingIsNeitherPassedNorFailedNorUndefined() throws IOException {
        catalog("""
                pending:
                  - id: shop.archive
                    phrase: "the order {order} is archived"
                """);
        write("features/a.feature", """
                Feature: Outcomes
                  Scenario: Passes
                    Given the fake store "shop" has "apples" set to 1
                  Scenario: Pending
                    Given the order "A-1" is archived
                  Scenario: Fails
                    Then the fake store "shop" should have "pears" equal to 1
                  Scenario: Undefined
                    Given nothing in the catalog says this
                """);
        ExecutionResult result = Engine.run(new ExecutionRequest(workspace, "local", Map.of()));

        assertThat(result.scenarios()).extracting(ScenarioResult::name, ScenarioResult::status).containsExactly(
                tuple("Passes", Status.PASSED),
                tuple("Pending", Status.PENDING),
                tuple("Fails", Status.FAILED),
                tuple("Undefined", Status.UNDEFINED));
        JsonNode counts = new ObjectMapper().readTree(result.reports().resolve("summary.json").toFile()).get("counts");
        assertThat(counts.get("passed").asInt()).isEqualTo(1);
        assertThat(counts.get("pending").asInt()).isEqualTo(1);
        assertThat(counts.get("failed").asInt()).isEqualTo(1);
        assertThat(counts.get("undefined").asInt()).isEqualTo(1);
        JsonNode steps = new ObjectMapper().readTree(result.reports().resolve("cucumber.json").toFile())
                .get(0).get("elements").get(1).get("steps");
        assertThat(steps.get(0).get("result").get("status").asText()).isEqualTo("pending");
        // JUnit XML has no pending: the scenario is skipped there, rather than failed.
        String junit = Files.readString(result.reports().resolve("junit.xml"));
        assertThat(junit).contains("tests=\"4\"").contains("skipped=\"1\"").contains("failures=\"2\"")
                .containsPattern("(?s)name=\"Pending\"[^>]*>\\s*<skipped message=\"The phrase 'the order");
    }

    @Test
    void pendingScenariosDoNotMakeTheExecutionUnsuccessfulUnlessAskedTo() throws IOException {
        catalog("""
                pending:
                  - id: shop.archive
                    phrase: "the order {order} is archived"
                """);
        ExecutionResult result = run("""
                Feature: Pending
                  Scenario: Passes
                    Given the fake store "shop" has "apples" set to 1
                  Scenario: Pending
                    Given the order "A-1" is archived
                """);

        assertThat(result.successful()).isTrue();
        assertThat(result.successful(true)).isFalse();
    }

    @Test
    void pendingPhraseTakesTablesDocStringsIntegersAndBothLanguages() throws IOException {
        catalog("""
                pending:
                  - id: shop.restock
                    phrase: "the shop restocks {count} apples"
                    parameters:
                      count: integer
                  - id: shop.restock
                    phrase: "il negozio rifornisce {count} mele"
                    language: it
                    parameters:
                      count: integer
                """);
        write("features/a.feature", """
                Feature: Pending
                  Scenario: Integer
                    Given the shop restocks 4 apples
                  Scenario: Table
                    Given the shop restocks 4 apples
                      | field | operator | value |
                      | a     | equals   | 1     |
                  Scenario: Doc string
                    Given the shop restocks ${count} apples
                      \"""
                      {}
                      \"""
                """);
        write("features/b.feature", """
                # language: it
                Funzionalità: In sospeso
                  Scenario: Italiano
                    Dato il negozio rifornisce 4 mele
                """);
        ExecutionResult result = Engine.run(new ExecutionRequest(workspace, "local", Map.of()));

        assertThat(result.scenarios()).extracting(ScenarioResult::status).containsOnly(Status.PENDING).hasSize(4);
    }

    @Test
    void pendingPhraseBecomesActiveWhenAnInstalledConnectorDeclaresItsId() throws IOException {
        // The fake connector declares "the fake store {source} has {key} set to {value}" as "fake.put".
        catalog("""
                pending:
                  - id: fake.put
                    phrase: "the shop {source} stocks {key} at {value}"
                    parameters:
                      value: integer
                """);
        ExecutionResult result = run("""
                Feature: Active
                  Scenario: Stock
                    Given the shop "shop" stocks "apples" at 5
                    Then the fake store "shop" should have "apples" equal to 5
                """);

        assertThat(result.scenarios().getFirst().steps()).extracting(StepResult::status)
                .containsExactly(Status.PASSED, Status.PASSED);
    }

    @Test
    void pendingPhraseWordedAsTheConnectorPhraseGivesWayToIt() throws IOException {
        catalog("""
                pending:
                  - id: fake.put
                    phrase: "the fake store {source} has {key} set to {value}"
                    parameters:
                      value: integer
                """);
        ExecutionResult result = run("""
                Feature: Active
                  Scenario: Stock
                    Given the fake store "shop" has "apples" set to 5
                    Then the fake store "shop" should have "apples" equal to 5
                """);

        assertThat(result.successful(true)).isTrue();
    }

    @Test
    void pendingPhraseWhoseParametersDoNotFitTheConnectorPhraseIsRejected() throws IOException {
        catalog("""
                pending:
                  - id: fake.put
                    phrase: "the shop {source} stocks {key} at {value}"
                """);
        feature("""
                Feature: Active
                  Scenario: Stock
                    Given the shop "shop" stocks "apples" at "5"
                """);

        assertThatThrownBy(() -> Engine.run(new ExecutionRequest(workspace, "local", Map.of())))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("pending[0]")
                .hasMessageContaining("{value}")
                .hasMessageContaining("integer");
    }

    @Test
    void compositePhraseWithAPendingStepIsPending() throws IOException {
        catalog("""
                pending:
                  - id: shop.archive
                    phrase: "the order {order} is archived"
                composites:
                  - phrase: "the order {order} is closed"
                    steps:
                      - the fake store "shop" has "{order}" set to 1
                      - the order "{order}" is archived
                      - the fake store "shop" has "{order}" set to 2
                  - phrase: "the day ends with order {order}"
                    steps:
                      - the order "{order}" is closed
                """);
        ExecutionResult result = run("""
                Feature: Composite
                  Scenario: Close
                    When the day ends with order "A-1"
                    Then the fake store "shop" should have "A-1" equal to 1
                """);

        ScenarioResult scenario = result.scenarios().getFirst();
        assertThat(scenario.status()).isEqualTo(Status.PENDING);
        assertThat(scenario.steps()).extracting(StepResult::status).containsExactly(Status.PENDING, Status.SKIPPED);
        StepResult closed = scenario.steps().getFirst().steps().getFirst();
        assertThat(closed.status()).isEqualTo(Status.PENDING);
        assertThat(closed.steps()).extracting(StepResult::status)
                .containsExactly(Status.PASSED, Status.PENDING, Status.SKIPPED);
    }

    @Test
    void effectiveCatalogShowsPendingPhrasesWithTheirId() throws IOException {
        catalog("""
                pending:
                  - id: shop.archive
                    phrase: "the order {order} is archived"
                  - id: fake.put
                    phrase: "the shop {source} stocks {key} at {value}"
                    parameters:
                      value: integer
                """);

        assertThat(Engine.catalog(workspace))
                .filteredOn(entry -> "workspace".equals(entry.origin()))
                .extracting(CatalogEntry::pattern, CatalogEntry::id, CatalogEntry::pending, CatalogEntry::target)
                .containsExactly(
                        tuple("the shop {source} stocks {key} at {value}", "fake.put", false, "fake.put"),
                        tuple("the order {order} is archived", "shop.archive", true, null));
    }

    @Test
    void pendingPhraseNeedsAnId() throws IOException {
        catalog("""
                pending:
                  - phrase: "the order {order} is archived"
                """);

        assertThatThrownBy(() -> Engine.catalog(workspace))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("pending[0].id");
    }

    @Test
    void pendingPhraseAmbiguousWithAnotherIsRejected() throws IOException {
        catalog("""
                pending:
                  - id: shop.other
                    phrase: "the fake store {store} has {key} set to {value}"
                    parameters:
                      value: integer
                """);

        assertThatThrownBy(() -> Engine.catalog(workspace))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Ambiguous catalog");
    }

    private void catalog(String content) throws IOException {
        write("catalog.yaml", content);
    }

    private void feature(String content) throws IOException {
        write("features/pending.feature", content);
    }

    private ExecutionResult run(String feature) throws IOException {
        feature(feature);
        return Engine.run(new ExecutionRequest(workspace, "local", Map.of()));
    }

    private void write(String name, String content) throws IOException {
        Path file = workspace.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
