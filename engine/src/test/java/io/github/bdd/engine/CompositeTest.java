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

class CompositeTest {

    @TempDir
    Path workspace;

    @Test
    void compositePhraseRunsItsStepsInOrderWithItsParameters() throws IOException {
        catalog("""
                composites:
                  - phrase: "the customer {name} has {count} points"
                    parameters:
                      count: integer
                    steps:
                      - 'the fake store "s" has "points" set to {count}'
                      - '{count} is added to "points" in the fake store "s"'
                      - 'the fake store "s" holds the record ''{"customer": "{name}"}'''
                """);

        ExecutionResult result = run("""
                Feature: Composite
                  Scenario: Points
                    Given the customer "Ann" has 3 points
                    Then the fake store "s" should have "points" equal to 6
                    And a record of the fake store "s" contains the JSON
                      \"\"\"
                      {"customer": "Ann"}
                      \"\"\"
                """);

        assertThat(result.successful()).as(result.toString()).isTrue();
    }

    @Test
    void parametersAreUsableInTablesAndMultilineTexts() throws IOException {
        catalog("""
                composites:
                  - phrase: "the order of {customer} is confirmed"
                    steps:
                      - 'the fake store "s" holds the record ''{"customer": "{customer}", "status": "CONFIRMED"}'''
                      - step: 'exactly 1 record exists in the fake store "s"'
                        table:
                          - [field, operator, value]
                          - [customer, equals, "{customer}"]
                          - [status, equals, CONFIRMED]
                      - step: 'a record of the fake store "s" contains the JSON'
                        text: '{"customer": "{customer}"}'
                """);

        ExecutionResult result = run("""
                Feature: Composite
                  Scenario: Order
                    Given the order of "Bob" is confirmed
                    Then exactly 0 records exist in the fake store "s"
                      | field    | operator | value |
                      | customer | equals   | Ann   |
                """);

        assertThat(result.successful()).as(result.toString()).isTrue();
    }

    @Test
    void compositeMayUseOtherCompositesAndBothLanguages() throws IOException {
        catalog("""
                composites:
                  - phrase:
                      en: "the customer {name} exists"
                      it: "il cliente {name} esiste"
                    steps:
                      - 'the fake store "s" holds the record ''{"customer": "{name}"}'''
                  - phrase: "{name} has a confirmed order"
                    steps:
                      - 'the customer {name} exists'
                      - 'the fake store "s" holds the record ''{"order": "{name}"}'''
                """);

        ExecutionResult result = run("""
                # language: it
                Funzionalità: Composite
                  Scenario: Annidate
                    Dato il cliente "Ann" esiste
                    E "Bob" has a confirmed order
                    Allora esistono esattamente 3 record nello store finto "s"
                """);

        assertThat(result.successful()).as(result.toString()).isTrue();
    }

    @Test
    void directCycleIsRefusedWithItsChain() throws IOException {
        catalog("""
                composites:
                  - phrase: "I loop"
                    steps:
                      - "I loop"
                """);

        assertThatThrownBy(() -> Engine.catalog(workspace))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("I loop -> I loop");
    }

    @Test
    void indirectCycleIsRefusedWithItsChain() throws IOException {
        catalog("""
                composites:
                  - phrase: "the first {x}"
                    steps:
                      - "the second {x}"
                  - phrase: "the second {x}"
                    steps:
                      - "the third {x}"
                  - phrase: "the third {x}"
                    steps:
                      - "the first {x}"
                """);

        assertThatThrownBy(() -> Engine.catalog(workspace))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("the first {x} -> the second {x} -> the third {x} -> the first {x}");
    }

    @Test
    void innerStepWithoutPhraseIsReportedAtLoad() throws IOException {
        catalog("""
                composites:
                  - phrase: "the customer {name} exists"
                    steps:
                      - 'the fake store "s" has "a" set to 1'
                      - 'the moon is full'
                """);

        assertThatThrownBy(() -> Engine.catalog(workspace))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("composites[0].steps[1]")
                .hasMessageContaining("the moon is full")
                .hasMessageContaining("no phrase");
    }

    @Test
    void innerStepCitingAnUnknownParameterIsRefused() throws IOException {
        catalog("""
                composites:
                  - phrase: "the customer {name} exists"
                    steps:
                      - 'the fake store "s" has {other} set to 1'
                """);

        assertThatThrownBy(() -> Engine.catalog(workspace))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("composites[0].steps[0]")
                .hasMessageContaining("{other}");
    }

    @Test
    void variablesOfInnerStepsStayAvailableToTheScenario() throws IOException {
        catalog("""
                composites:
                  - phrase: "an order is placed"
                    steps:
                      - 'the fake store "s" holds the record ''{"id": "o-${uuid:order}"}'''
                      - 'I save the "id" of a record of the fake store "s" as "order_id"'
                """);

        ExecutionResult result = run("""
                Feature: Composite
                  Scenario: Variables
                    Given an order is placed
                    Then a record of the fake store "s" contains the JSON
                      \"\"\"
                      {"id": "${order_id}"}
                      \"\"\"
                """);

        assertThat(result.successful()).as(result.toString()).isTrue();
    }

    @Test
    void failedInnerStepFailsTheCompositeAndSaysWhich() throws IOException {
        catalog("""
                composites:
                  - phrase: "the customer {name} has a point"
                    steps:
                      - 'the fake store "s" has {name} set to 1'
                      - 'the fake store "s" should have {name} equal to 2'
                      - 'the fake store "s" has "never" set to 1'
                """);

        ExecutionResult result = run("""
                Feature: Composite
                  Scenario: Failing
                    Given the customer "Ann" has a point
                    Then the fake store "s" should have "never" equal to 1
                """);

        ScenarioResult scenario = result.scenarios().getFirst();
        assertThat(scenario.status()).isEqualTo(Status.FAILED);
        StepResult composite = scenario.steps().getFirst();
        assertThat(composite.status()).isEqualTo(Status.FAILED);
        assertThat(composite.error())
                .contains("step 2")
                .contains("the fake store \"s\" should have \"Ann\" equal to 2")
                .contains("Expected \"Ann\" to equal 2 but was 1");
        assertThat(scenario.steps().getLast().status()).isEqualTo(Status.SKIPPED);
    }

    @Test
    void reportShowsTheCompositeAsOneStepWithItsInnerStepsConsultable() throws IOException {
        catalog("""
                composites:
                  - phrase: "the customer {name} has a point"
                    steps:
                      - 'the fake store "s" has {name} set to 1'
                      - 'the fake store "s" should have {name} equal to 2'
                """);

        ExecutionResult result = run("""
                Feature: Composite
                  Scenario: Reported
                    Given the customer "Ann" has a point
                """);

        List<StepResult> steps = result.scenarios().getFirst().steps();
        assertThat(steps).hasSize(1);
        assertThat(steps.getFirst().text()).isEqualTo("the customer \"Ann\" has a point");
        assertThat(steps.getFirst().inner()).extracting(StepResult::text, StepResult::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "the fake store \"s\" has \"Ann\" set to 1", Status.PASSED),
                        org.assertj.core.groups.Tuple.tuple(
                                "the fake store \"s\" should have \"Ann\" equal to 2", Status.FAILED));
        assertThat(steps.getFirst().inner().getLast().error()).contains("Expected \"Ann\" to equal 2");

        String summary = Files.readString(result.reports().resolve("summary.json"));
        assertThat(summary).contains("the fake store \\\"s\\\" has \\\"Ann\\\" set to 1");
    }

    @Test
    void catalogListsCompositePhrasesWithTheirSteps() throws IOException {
        catalog("""
                composites:
                  - phrase: "the customer {name} exists"
                    steps:
                      - 'the fake store "s" has {name} set to 1'
                """);

        CatalogEntry entry = Engine.catalog(workspace).stream()
                .filter(candidate -> candidate.pattern().equals("the customer {name} exists"))
                .findFirst().orElseThrow();

        assertThat(entry.origin()).isEqualTo("workspace");
        assertThat(entry.target()).isEqualTo("composite");
        assertThat(entry.steps()).containsExactly("the fake store \"s\" has {name} set to 1");
        assertThat(entry.parameters()).extracting(parameter -> parameter.name()).containsExactly("name");
    }

    @Test
    void compositeContainingAPendingPhraseIsPending() throws IOException {
        catalog("""
                pending:
                  - id: shop.refund
                    phrase: "the order {order} is refunded"
                composites:
                  - phrase: "the customer returns {order}"
                    steps:
                      - 'the fake store "s" holds the record ''{"returned": "{order}"}'''
                      - 'the order "{order}" is refunded'
                """);

        ExecutionResult result = run("""
                Feature: Composite
                  Scenario: Return
                    Given the customer returns "A1"
                """);

        ScenarioResult scenario = result.scenarios().getFirst();
        assertThat(scenario.status()).as(result.toString()).isEqualTo(Status.PENDING);
        assertThat(result.successful()).isTrue();
        assertThat(result.successful(true)).isFalse();
    }

    private void catalog(String content) throws IOException {
        Files.writeString(workspace.resolve("catalog.yaml"), content);
    }

    private ExecutionResult run(String feature) throws IOException {
        Files.writeString(workspace.resolve("sources.yaml"), """
                profiles:
                  local:
                    sources:
                      s:
                        type: fake
                """);
        Path file = workspace.resolve("features/composite.feature");
        Files.createDirectories(file.getParent());
        Files.writeString(file, feature);
        return Engine.run(new ExecutionRequest(workspace, "local", Map.of()));
    }
}
