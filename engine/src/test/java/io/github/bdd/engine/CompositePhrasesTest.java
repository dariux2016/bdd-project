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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class CompositePhrasesTest {

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
    void compositeRunsItsStepsInOrderWithItsParameters() throws IOException {
        catalog("""
                composites:
                  - phrase: "the shop {store} has {count} apples restocked"
                    steps:
                      - the fake store "{store}" has "apples" set to 1
                      - '{count} is added to "apples" in the fake store "{store}"'
                """);
        ExecutionResult result = run("""
                Feature: Composite
                  Scenario: Restock
                    Given the shop "shop" has "4" apples restocked
                    Then the fake store "shop" should have "apples" equal to 5
                """);

        assertThat(errors(result)).isEmpty();
        assertThat(result.successful()).isTrue();
    }

    @Test
    void parametersReachTheTablesAndDocStringsOfTheSteps() throws IOException {
        catalog("""
                composites:
                  - phrase: "the customer {customer} has a confirmed order"
                    steps:
                      - >-
                        the fake store "shop" holds the record '{"customer": "{customer}", "status": "CONFIRMED"}'
                      - step: a record exists in the fake store "shop"
                        table:
                          - [field, operator, value]
                          - [customer, equals, "{customer}"]
                      - step: a record of the fake store "shop" contains the JSON
                        docString: |
                          {"customer": "{customer}", "status": "CONFIRMED"}
                """);
        ExecutionResult result = run("""
                Feature: Composite
                  Scenario: Order
                    Given the customer "Ann" has a confirmed order
                    Then exactly 1 record exists in the fake store "shop"
                      | field    | operator | value |
                      | customer | equals   | Ann   |
                """);

        assertThat(errors(result)).isEmpty();
        assertThat(result.successful()).isTrue();
    }

    @Test
    void tableOfTheCompositeStepIsPassedOnToTheStepsThatAskForIt() throws IOException {
        catalog("""
                composites:
                  - phrase: "the shop has a record matching"
                    steps:
                      - step: a record exists in the fake store "shop"
                        table: "{table}"
                      - step: exactly 1 record exists in the fake store "shop"
                        table: "{table}"
                """);
        ExecutionResult result = run("""
                Feature: Composite
                  Scenario: Table
                    Given the fake store "shop" holds the record '{"status": "PAID"}'
                    Then the shop has a record matching
                      | field  | operator | value |
                      | status | equals   | PAID  |
                    And the shop has a record matching
                      | field  | operator | value |
                      | status | equals   | NEW   |
                """);

        ScenarioResult scenario = result.scenarios().getFirst();
        assertThat(scenario.steps()).extracting(StepResult::status)
                .containsExactly(Status.PASSED, Status.PASSED, Status.FAILED);
    }

    @Test
    void compositeStepWithoutTheTableItPassesOnFails() throws IOException {
        catalog("""
                composites:
                  - phrase: "the shop has a record matching"
                    steps:
                      - step: a record exists in the fake store "shop"
                        table: "{table}"
                """);
        ExecutionResult result = run("""
                Feature: Composite
                  Scenario: No table
                    Then the shop has a record matching
                """);

        assertThat(errors(result)).containsExactly(
                "This step needs a table, which its composite phrase 'the shop has a record matching' passes on to its steps");
    }

    @Test
    void docStringOfTheCompositeStepIsAParameterOfItsSteps() throws IOException {
        catalog("""
                composites:
                  - phrase: "the shop {store} holds and shows the JSON"
                    steps:
                      - >-
                        the fake store "{store}" holds the record '{"kind": "order"}'
                      - step: a record of the fake store "{store}" contains the JSON
                        docString: "{docString}"
                """);
        ExecutionResult result = run("""
                Feature: Composite
                  Scenario: Doc string
                    Then the shop "shop" holds and shows the JSON
                      \"""
                      {"kind": "order"}
                      \"""
                """);

        assertThat(errors(result)).isEmpty();
        assertThat(result.successful()).isTrue();
    }

    @Test
    void compositeMayUseOtherComposites() throws IOException {
        catalog("""
                composites:
                  - phrase: "the shop {store} is open"
                    steps:
                      - the shop "{store}" is stocked
                      - 1 is added to "apples" in the fake store "{store}"
                  - phrase: "the shop {store} is stocked"
                    steps:
                      - the fake store "{store}" has "apples" set to 10
                """);
        ExecutionResult result = run("""
                Feature: Composite
                  Scenario: Nested
                    Given the shop "shop" is open
                    Then the fake store "shop" should have "apples" equal to 11
                """);

        assertThat(errors(result)).isEmpty();
        assertThat(result.successful()).isTrue();
        StepResult open = result.scenarios().getFirst().steps().getFirst();
        assertThat(open.steps()).extracting(StepResult::text, StepResult::status).containsExactly(
                tuple("the shop \"shop\" is stocked", Status.PASSED),
                tuple("1 is added to \"apples\" in the fake store \"shop\"", Status.PASSED));
        assertThat(open.steps().getFirst().steps()).extracting(StepResult::text)
                .containsExactly("the fake store \"shop\" has \"apples\" set to 10");
    }

    @Test
    void failureInsideANestedCompositeNamesEachLevel() throws IOException {
        catalog("""
                composites:
                  - phrase: "the shop {store} is open"
                    steps:
                      - the shop "{store}" is stocked
                  - phrase: "the shop {store} is stocked"
                    steps:
                      - the fake store "{store}" has "apples" set to 10
                      - the fake store "{store}" should have "apples" equal to 11
                """);
        ExecutionResult result = run("""
                Feature: Composite
                  Scenario: Nested failure
                    Given the shop "shop" is open
                """);

        StepResult open = result.scenarios().getFirst().steps().getFirst();
        assertThat(open.status()).isEqualTo(Status.FAILED);
        assertThat(open.error()).isEqualTo("Step 1 of the composite phrase 'the shop {store} is open', "
                + "'the shop \"shop\" is stocked', failed: Step 2 of the composite phrase 'the shop {store} is stocked', "
                + "'the fake store \"shop\" should have \"apples\" equal to 11', failed: "
                + "Expected \"apples\" to equal 11 but was 10");
        assertThat(open.steps().getFirst().steps()).extracting(StepResult::status)
                .containsExactly(Status.PASSED, Status.FAILED);
    }

    @Test
    void directCycleIsRefusedWithItsChain() throws IOException {
        catalog("""
                composites:
                  - phrase: "the shop {store} is open"
                    steps:
                      - the shop "{store}" is open
                """);

        assertThatThrownBy(() -> Engine.catalog(workspace))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("cycle")
                .hasMessageContaining("'the shop {store} is open' -> 'the shop {store} is open'");
    }

    @Test
    void indirectCycleIsRefusedWithItsChain() throws IOException {
        catalog("""
                composites:
                  - phrase: "the shop {store} is open"
                    steps:
                      - the shop "{store}" is stocked
                  - phrase: "the shop {store} is stocked"
                    steps:
                      - the fake store "{store}" has "apples" set to 10
                      - the shop "{store}" is ready
                  - phrase: "the shop {store} is ready"
                    steps:
                      - the shop "{store}" is open
                """);

        assertThatThrownBy(() -> Engine.catalog(workspace))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("'the shop {store} is open' -> 'the shop {store} is stocked' -> "
                        + "'the shop {store} is ready' -> 'the shop {store} is open'");
    }

    @Test
    void stepMatchingNoPhraseIsReportedAtLoad() throws IOException {
        catalog("""
                composites:
                  - phrase: "the shop {store} is open"
                    steps:
                      - the fake store "{store}" has "apples" set to 10
                      - the fake store "{store}" opens its doors
                """);

        assertThatThrownBy(() -> Engine.catalog(workspace))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("composites[0].steps[1]")
                .hasMessageContaining("the fake store \"{store}\" opens its doors");
    }

    @Test
    void compositeCannotTakeBothATableAndADocString() throws IOException {
        catalog("""
                composites:
                  - phrase: "the shop checks"
                    steps:
                      - step: a record exists in the fake store "shop"
                        table: "{table}"
                      - step: a record of the fake store "shop" contains the JSON
                        docString: "{docString}"
                """);

        assertThatThrownBy(() -> Engine.catalog(workspace))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("composites[0]")
                .hasMessageContaining("both");
    }

    @Test
    void variablesSavedByItsStepsHoldForTheRestOfTheScenario() throws IOException {
        catalog("""
                composites:
                  - phrase: "an order of {customer} is placed and its id saved as {name}"
                    steps:
                      - >-
                        the fake store "shop" holds the record '{"id": 42, "customer": "{customer}"}'
                      - step: I save the "id" of a record of the fake store "shop" as "{name}"
                        table:
                          - [field, operator, value]
                          - [customer, equals, "{customer}"]
                """);
        ExecutionResult result = run("""
                Feature: Composite
                  Scenario: Variables
                    Given an order of "Ann" is placed and its id saved as "orderId"
                    Then the fake store "shop" holds the record '{"ref": "${orderId}"}'
                    And a record exists in the fake store "shop"
                      | field | operator | value |
                      | ref   | equals   | 42    |
                """);

        assertThat(errors(result)).isEmpty();
        assertThat(result.successful()).isTrue();
    }

    @Test
    void variableNamedLikeAParameterIsNotReplacedByIt() throws IOException {
        catalog("""
                composites:
                  - phrase: "the order {orderId} is referenced"
                    steps:
                      - >-
                        the fake store "shop" holds the record '{"ref": "${orderId}", "label": "{orderId}"}'
                """);
        ExecutionResult result = run("""
                Feature: Composite
                  Scenario: Variable and parameter with one name
                    Given the fake store "shop" holds the record '{"id": 42}'
                    And I save the "id" of a record of the fake store "shop" as "orderId"
                      | field | operator | value |
                      | id    | equals   | 42    |
                    When the order "label" is referenced
                    Then a record exists in the fake store "shop"
                      | field | operator | value |
                      | ref   | equals   | 42    |
                      | label | equals   | label |
                """);

        assertThat(errors(result)).isEmpty();
        assertThat(result.successful()).isTrue();
    }

    @Test
    void failingStepFailsTheCompositeSayingWhichAndWhy() throws IOException {
        catalog("""
                composites:
                  - phrase: "the shop {store} is checked"
                    steps:
                      - the fake store "{store}" has "apples" set to 3
                      - the fake store "{store}" should have "apples" equal to 4
                      - the fake store "{store}" has "pears" set to 1
                """);
        ExecutionResult result = run("""
                Feature: Composite
                  Scenario: Failure
                    Given the shop "shop" is checked
                    Then the fake store "shop" should have "apples" equal to 3
                """);

        ScenarioResult scenario = result.scenarios().getFirst();
        assertThat(scenario.status()).isEqualTo(Status.FAILED);
        assertThat(scenario.steps()).extracting(StepResult::status).containsExactly(Status.FAILED, Status.SKIPPED);
        StepResult composite = scenario.steps().getFirst();
        assertThat(composite.error())
                .contains("Step 2 of the composite phrase 'the shop {store} is checked'")
                .contains("the fake store \"shop\" should have \"apples\" equal to 4")
                .contains("Expected \"apples\" to equal 4 but was 3");
        assertThat(composite.steps()).extracting(StepResult::status, StepResult::error).containsExactly(
                tuple(Status.PASSED, null),
                tuple(Status.FAILED, "Expected \"apples\" to equal 4 but was 3"),
                tuple(Status.SKIPPED, null));
    }

    @Test
    void errorInATableOfTheCatalogCitesWhereItIs() throws IOException {
        catalog("""
                composites:
                  - phrase: "the shop has a paid record"
                    steps:
                      - step: a record exists in the fake store "shop"
                        table:
                          - [field, operator, value]
                          - [status, above, PAID]
                """);
        ExecutionResult result = run("""
                Feature: Composite
                  Scenario: Table error
                    Then the shop has a paid record
                """);

        assertThat(errors(result)).singleElement().asString()
                .contains("catalog.yaml: composites[0].steps[0].table[1]: unknown operator 'above'");
    }

    @Test
    void reportsShowTheCompositeAsOneStepWithItsStepsInside() throws IOException {
        catalog("""
                composites:
                  - phrase: "the shop {store} is stocked"
                    steps:
                      - the fake store "{store}" has "apples" set to 10
                      - 2 is added to "apples" in the fake store "{store}"
                """);
        ExecutionResult result = run("""
                Feature: Composite
                  Scenario: Reports
                    Given the shop "shop" is stocked
                """);

        assertThat(result.scenarios().getFirst().steps()).singleElement().satisfies(step -> {
            assertThat(step.text()).isEqualTo("the shop \"shop\" is stocked");
            assertThat(step.steps()).extracting(StepResult::keyword, StepResult::text, StepResult::status)
                    .containsExactly(
                            tuple("*", "the fake store \"shop\" has \"apples\" set to 10", Status.PASSED),
                            tuple("*", "2 is added to \"apples\" in the fake store \"shop\"", Status.PASSED));
        });
        JsonNode steps = new ObjectMapper().readTree(result.reports().resolve("cucumber.json").toFile())
                .get(0).get("elements").get(0).get("steps");
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).get("output").toString())
                .contains("the fake store \\\"shop\\\" has \\\"apples\\\" set to 10")
                .contains("2 is added to \\\"apples\\\" in the fake store \\\"shop\\\"");
    }

    @Test
    void effectiveCatalogShowsCompositesWithTheirSteps() throws IOException {
        catalog("""
                composites:
                  - phrase: "il negozio {store} è rifornito"
                    language: it
                    steps:
                      - lo store finto "{store}" ha "mele" impostato a 10
                """);

        CatalogEntry composite = Engine.catalog(workspace).stream()
                .filter(entry -> entry.pattern().equals("il negozio {store} è rifornito"))
                .findFirst().orElseThrow();

        assertThat(composite.language()).isEqualTo("it");
        assertThat(composite.origin()).isEqualTo("workspace");
        assertThat(composite.target()).isNull();
        assertThat(composite.parameters()).extracting(parameter -> parameter.name()).containsExactly("store");
        assertThat(composite.steps()).containsExactly("lo store finto \"{store}\" ha \"mele\" impostato a 10");
    }

    private static List<String> errors(ExecutionResult result) {
        return result.scenarios().stream()
                .flatMap(scenario -> scenario.steps().stream())
                .map(StepResult::error)
                .filter(error -> error != null)
                .toList();
    }

    private void catalog(String content) throws IOException {
        write("catalog.yaml", content);
    }

    private ExecutionResult run(String feature) throws IOException {
        write("features/composite.feature", feature.replace("\\\"", "\""));
        return Engine.run(new ExecutionRequest(workspace, "local", Map.of()));
    }

    private void write(String name, String content) throws IOException {
        Path file = workspace.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
