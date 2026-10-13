package io.github.bdd.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VariablesTest {

    @TempDir
    Path workspace;

    @Test
    void savedFieldIsReusedInTheStepsThatFollow() throws IOException {
        ExecutionResult result = run("""
                Feature: Variables
                  Scenario: Reuse an id
                    Given the fake store "shop" holds the record '{"id": 7, "status": "NEW"}'
                    When I save the "id" of a record of the fake store "shop" as "orderId"
                    And the fake store "shop" holds the record '{"ref": "${orderId}"}'
                    Then a record exists in the fake store "shop"
                      | field | operator | value        |
                      | ref   | equals   | ${orderId}   |
                    And a record of the fake store "shop" contains the JSON
                      \"\"\"
                      {"ref": "${orderId}"}
                      \"\"\"
                """);
        assertThat(errors(result)).isEmpty();
        assertThat(result.successful()).isTrue();
        assertThat(result.successful()).isTrue();
    }

    @Test
    void fieldIsSavedFromTheFirstRecordSatisfyingTheFilter() throws IOException {
        ExecutionResult result = run("""
                Feature: Variables
                  Scenario: Pick a record
                    Given the fake store "shop" holds the record '{"id": 1, "status": "NEW", "n": 2}'
                    And the fake store "shop" holds the record '{"id": 2, "status": "PAID", "n": 5}'
                    When I save the "n" of a record of the fake store "shop" as "paidCount"
                      | field  | operator | value |
                      | status | equals   | PAID  |
                    Then the fake store "shop" holds the record '{"paid": "${paidCount}"}'
                    And a record exists in the fake store "shop"
                      | field | operator | value |
                      | paid  | equals   | 5     |
                """);
        assertThat(errors(result)).isEmpty();
        assertThat(result.successful()).isTrue();
    }

    @Test
    void integerParametersMayBeVariables() throws IOException {
        ExecutionResult result = run("""
                Feature: Variables
                  Scenario: A count from a variable
                    Given the fake store "shop" holds the record '{"n": 2}'
                    And the fake store "shop" holds the record '{"n": 3}'
                    When I save the "n" of a record of the fake store "shop" as "expected"
                      | field | operator | value |
                      | n     | equals   | 2     |
                    Then exactly ${expected} records exist in the fake store "shop"
                """);
        assertThat(errors(result)).isEmpty();
        assertThat(result.successful()).isTrue();
    }

    @Test
    void environmentVariablesHaveTheirOwnSyntax() throws IOException {
        ExecutionResult result = run("""
                Feature: Variables
                  Scenario: Environment
                    Given the fake store "shop" holds the record '{"host": "${env:SHOP_HOST}"}'
                    Then a record of the fake store "shop" equals the JSON
                      \"\"\"
                      {"host": "shop.example.com"}
                      \"\"\"
                """, Map.of("SHOP_HOST", "shop.example.com"));
        assertThat(errors(result)).isEmpty();
        assertThat(result.successful()).isTrue();
    }

    @Test
    void unsetEnvironmentVariableIsNamed() throws IOException {
        ExecutionResult result = run("""
                Feature: Variables
                  Scenario: Environment
                    Given the fake store "shop" holds the record '{"host": "${env:SHOP_HOST}"}'
                """);
        assertThat(errors(result)).containsExactly("Environment variable 'SHOP_HOST' is not set");
    }

    @Test
    void scenarioVariableIsNotTheEnvironmentOne() throws IOException {
        ExecutionResult result = run("""
                Feature: Variables
                  Scenario: Same name
                    Given the fake store "shop" holds the record '{"host": "${SHOP_HOST}"}'
                """, Map.of("SHOP_HOST", "shop.example.com"));
        assertThat(errors(result)).containsExactly("Variable 'SHOP_HOST' is not defined in this scenario");
    }

    @Test
    void generatedValueIsTheSameWithinAScenarioAndDiffersBetweenScenarios() throws IOException {
        ExecutionResult result = run("""
                Feature: Variables
                  Scenario: First
                    Given the fake store "shop" holds the record '{"a": "${uuid:id}", "b": "${uuid:id}"}'
                    Then exactly 1 record exists in the fake store "shop"
                      | field | operator | value |
                      | a     | equals   | ${id} |
                      | b     | equals   | ${id} |
                    And a record exists in the fake store "shop"
                      | field | operator | value                                                       |
                      | a     | regex    | ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ |

                  Scenario: Second
                    Given the fake store "shop" holds the record '{"a": "${uuid:id}", "b": "${uuid:id}"}'
                    Then exactly 1 record exists in the fake store "shop"
                      | field | operator | value |
                      | a     | equals   | ${id} |
                """);
        assertThat(errors(result)).isEmpty();
        assertThat(result.successful()).isTrue();
        assertThat(result.scenarios()).hasSize(2);
    }

    @Test
    void instantsAndRandomNumbersAreGenerated() throws IOException {
        ExecutionResult result = run("""
                Feature: Variables
                  Scenario: Generators
                    Given the fake store "shop" holds the record '{"t": "${now:start}", "later": "${now+1d:end}", "earlier": "${now-2h:before}", "r": "${random:number}"}'
                    Then a record exists in the fake store "shop"
                      | field   | operator     | value          |
                      | t       | equals       | ${start}       |
                      | later   | greater than | ${start}       |
                      | earlier | less than    | ${start}       |
                      | r       | regex        | ^[0-9]+$       |
                      | r       | equals       | ${number}      |
                """);
        assertThat(errors(result)).isEmpty();
        assertThat(result.successful()).isTrue();
    }

    @Test
    void variablesHoldOnlyInTheScenarioThatDefinesThem() throws IOException {
        ExecutionResult result = run("""
                Feature: Variables
                  Scenario: Defines
                    Given the fake store "shop" holds the record '{"id": 7}'
                    When I save the "id" of a record of the fake store "shop" as "orderId"

                  Scenario: Uses
                    Given the fake store "shop" holds the record '{"ref": "${orderId}"}'
                """);
        assertThat(result.scenarios().get(0).status()).isEqualTo(Status.PASSED);
        assertThat(result.scenarios().get(1).status()).isEqualTo(Status.FAILED);
        assertThat(errors(result)).containsExactly("Variable 'orderId' is not defined in this scenario");
    }

    @Test
    void generatorWithoutANameIsRefused() throws IOException {
        ExecutionResult result = run("""
                Feature: Variables
                  Scenario: Nameless
                    Given the fake store "shop" holds the record '{"id": "${uuid}"}'
                """);
        assertThat(errors(result)).singleElement().asString().contains("needs a name");
    }

    private static List<String> errors(ExecutionResult result) {
        return result.scenarios().stream()
                .flatMap(scenario -> scenario.steps().stream())
                .map(StepResult::error)
                .filter(error -> error != null)
                .toList();
    }

    private ExecutionResult run(String feature) throws IOException {
        return run(feature, Map.of());
    }

    private ExecutionResult run(String feature, Map<String, String> environment) throws IOException {
        Files.writeString(workspace.resolve("sources.yaml"), """
                profiles:
                  local:
                    sources:
                      shop:
                        type: fake
                """);
        Files.writeString(workspace.resolve("settings.yaml"), """
                verification:
                  timeout: 0ms
                """);
        Path file = workspace.resolve("features/variables.feature");
        Files.createDirectories(file.getParent());
        Files.writeString(file, feature.replace("\\\"", "\""));
        return Engine.run(new ExecutionRequest(workspace, "local", environment));
    }
}
