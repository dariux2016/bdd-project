package io.github.bdd.engine;

import io.github.bdd.engine.FeatureValidation.StepValidation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationTest {

    @TempDir
    Path workspace;

    @Test
    void everyStepIsMatchedToThePhraseItWouldRunWith() {
        FeatureValidation validation = Engine.validate(workspace, """
                Feature: Store
                  Scenario: Counters
                    Given the fake store "s" has "k" set to 3
                    Then the fake store "s" should have "k" equal to 3
                """);

        assertThat(validation.errors()).isEmpty();
        assertThat(validation.language()).isEqualTo("en");
        assertThat(validation.steps()).extracting(StepValidation::line, StepValidation::status, StepValidation::phrase)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(3, "matched",
                                "the fake store {source} has {key} set to {value}"),
                        org.assertj.core.groups.Tuple.tuple(4, "matched",
                                "the fake store {source} should have {key} equal to {value}"));
        assertThat(validation.steps().getFirst().origin()).isEqualTo("connector:fake");
    }

    @Test
    void stepWithoutAPhraseIsUndefinedWithItsLineAndAReason() {
        FeatureValidation validation = Engine.validate(workspace, """
                Feature: Store
                  Scenario: Typo
                    Given the fake store "s" has "k" set to 3
                    When the basket is emptied
                """);

        StepValidation undefined = validation.steps().get(1);
        assertThat(undefined.line()).isEqualTo(4);
        assertThat(undefined.status()).isEqualTo("undefined");
        assertThat(undefined.phrase()).isNull();
        assertThat(undefined.message()).contains("No phrase of the catalog matches");
    }

    @Test
    void pendingPhraseIsReportedAsPendingNotUndefined() throws IOException {
        Files.writeString(workspace.resolve("catalog.yaml"), """
                pending:
                  - id: shop.refund
                    phrase: "the order {order} is refunded"
                """);

        FeatureValidation validation = Engine.validate(workspace, """
                Feature: Shop
                  Scenario: Refund
                    When the order "A1" is refunded
                """);

        StepValidation step = validation.steps().getFirst();
        assertThat(step.status()).isEqualTo("pending");
        assertThat(step.phrase()).isEqualTo("the order {order} is refunded");
    }

    @Test
    void italianFeatureIsValidatedAgainstTheItalianPhrases() {
        FeatureValidation validation = Engine.validate(workspace, """
                # language: it
                Funzionalità: Store
                  Scenario: Contatori
                    Dato lo store finto "s" ha "k" impostato a 3
                """);

        assertThat(validation.language()).isEqualTo("it");
        assertThat(validation.steps()).extracting(StepValidation::status).containsExactly("matched");
    }

    @Test
    void workspaceAliasIsMatchedAndSaysWhereItComesFrom() throws IOException {
        Files.writeString(workspace.resolve("catalog.yaml"), """
                aliases:
                  - phrase: "{key} of the fake store {source} is {value}"
                    for: "the fake store {source} has {key} set to {value}"
                """);

        FeatureValidation validation = Engine.validate(workspace, """
                Feature: Aliases
                  Scenario: One
                    Given "k" of the fake store "s" is 3
                """);

        assertThat(validation.steps().getFirst().status()).isEqualTo("matched");
        assertThat(validation.steps().getFirst().origin()).isEqualTo("workspace");
    }

    @Test
    void scenarioOutlineStepsAreValidatedOnceAtTheirOwnLine() {
        FeatureValidation validation = Engine.validate(workspace, """
                Feature: Store
                  Scenario Outline: Many
                    Given the fake store "s" has "k" set to <n>
                    When the basket is emptied
                    Examples:
                      | n |
                      | 1 |
                      | 2 |
                """);

        assertThat(validation.steps()).extracting(StepValidation::line, StepValidation::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(3, "matched"),
                        org.assertj.core.groups.Tuple.tuple(4, "undefined"));
    }

    @Test
    void syntaxErrorsAreReportedWithLineAndMessage() {
        FeatureValidation validation = Engine.validate(workspace, """
                Feature: Broken
                  Scenario: Broken
                    Given the fake store "s" has "k" set to 3
                  this is not gherkin
                """);

        assertThat(validation.errors()).isNotEmpty();
        assertThat(validation.errors().getFirst().line()).isEqualTo(4);
        assertThat(validation.errors().getFirst().message()).isNotBlank();
        assertThat(validation.steps()).isEmpty();
    }

    @Test
    void emptyTextHasNothingToValidate() {
        FeatureValidation validation = Engine.validate(workspace, "");

        assertThat(validation.errors()).isEmpty();
        assertThat(validation.steps()).isEmpty();
    }

    @Test
    void validationAgreesWithExecution() throws IOException {
        String feature = """
                Feature: Agreement
                  Scenario: Mixed
                    Given the fake store "s" has "k" set to 3
                    When the basket is emptied
                    Then the fake store "s" should have "k" equal to 3
                """;
        Files.writeString(workspace.resolve("sources.yaml"), """
                profiles:
                  local:
                    sources:
                      s:
                        type: fake
                """);
        Path file = workspace.resolve("features/agreement.feature");
        Files.createDirectories(file.getParent());
        Files.writeString(file, feature);

        FeatureValidation validation = Engine.validate(workspace, feature);
        ExecutionResult result = Engine.run(new ExecutionRequest(workspace, "local", Map.of()));

        List<String> executed = result.scenarios().getFirst().steps().stream()
                .map(step -> step.status() == Status.UNDEFINED ? "undefined" : "matched").toList();
        assertThat(validation.steps()).extracting(StepValidation::status).isEqualTo(executed);
    }
}
