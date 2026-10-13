package io.github.bdd.engine;

import io.github.bdd.plugin.Verification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogTest {

    @TempDir
    Path workspace;

    @Test
    void italianFeatureGivesTheOutcomeOfItsEnglishVersion() throws IOException {
        ExecutionResult english = run("""
                Feature: Store
                  Scenario: Counters
                    Given the fake store "s" has "k" set to 3
                    When 2 is added to "k" in the fake store "s"
                    Then the fake store "s" should have "k" equal to 5
                    And exactly 0 records exist in the fake store "s"
                """);
        ExecutionResult italian = run("""
                # language: it
                Funzionalità: Store
                  Scenario: Contatori
                    Dato lo store finto "s" ha "k" impostato a 3
                    Quando 2 viene aggiunto a "k" nello store finto "s"
                    Allora lo store finto "s" deve avere "k" uguale a 5
                    E esistono esattamente 0 record nello store finto "s"
                """);

        assertThat(english.successful()).isTrue();
        assertThat(italian.successful()).isTrue();
        assertThat(italian.scenarios().getFirst().steps()).extracting(StepResult::status)
                .isEqualTo(english.scenarios().getFirst().steps().stream().map(StepResult::status).toList());
    }

    @Test
    void italianFeatureFailsLikeItsEnglishVersion() throws IOException {
        ExecutionResult italian = run("""
                # language: it
                Funzionalità: Store
                  Scenario: Contatori
                    Dato lo store finto "s" ha "k" impostato a 3
                    Allora lo store finto "s" deve avere "k" uguale a 4
                """);

        assertThat(italian.successful()).isFalse();
        assertThat(italian.scenarios().getFirst().steps().getLast().error())
                .isEqualTo("Expected \"k\" to equal 4 but was 3");
    }

    @Test
    void workspaceAliasLeadsToTheSameAction() throws IOException {
        Files.writeString(workspace.resolve("catalog.yaml"), """
                aliases:
                  - phrase: "{key} of the fake store {source} is {value}"
                    for: "the fake store {source} has {key} set to {value}"
                  - phrase: "nello store finto {source} {key} vale {value}"
                    for: "the fake store {source} should have {key} equal to {value}"
                    language: it
                """);
        ExecutionResult result = run("""
                Feature: Aliases
                  Scenario: Both
                    Given "k" of the fake store "s" is 3
                    Then nello store finto "s" "k" vale 3
                    And the fake store "s" should have "k" equal to 3
                """);

        assertThat(result.successful()).isTrue();
    }

    @Test
    void aliasWithOtherParametersIsRefused() throws IOException {
        Files.writeString(workspace.resolve("catalog.yaml"), """
                aliases:
                  - phrase: "{key} of the fake store {source} is set"
                    for: "the fake store {source} has {key} set to {value}"
                """);

        assertThatThrownBy(() -> Engine.catalog(workspace))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("aliases[0].phrase")
                .hasMessageContaining("{key}, {source}")
                .hasMessageContaining("{key}, {source}, {value}");
    }

    @Test
    void aliasForUnknownPhraseIsRefused() throws IOException {
        Files.writeString(workspace.resolve("catalog.yaml"), """
                aliases:
                  - phrase: "x {key}"
                    for: "no such phrase {key}"
                """);

        assertThatThrownBy(() -> Engine.catalog(workspace))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("aliases[0].for")
                .hasMessageContaining("no such phrase {key}");
    }

    @Test
    void phrasesThatCanMatchTheSameStepAreReportedWithBoth() throws IOException {
        Files.writeString(workspace.resolve("catalog.yaml"), """
                aliases:
                  - phrase: "the fake store {source} should have {key} equal to {value}"
                    for: "the fake store {source} has {key} set to {value}"
                """);

        assertThatThrownBy(() -> Engine.catalog(workspace))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Ambiguous")
                .hasMessageContaining("the fake store {source} should have {key} equal to {value}' (connector:fake)")
                .hasMessageContaining("the fake store {source} should have {key} equal to {value}' (workspace)");
    }

    @Test
    void ambiguityIsFoundThroughOptionalTextAndAlternatives() throws IOException {
        Files.writeString(workspace.resolve("catalog.yaml"), """
                aliases:
                  - phrase: "I wait {seconds} second"
                    for: "I wait {seconds} second(s)"
                """);

        assertThatThrownBy(() -> Engine.catalog(workspace))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("I wait {seconds} second' (workspace)")
                .hasMessageContaining("I wait {seconds} second(s)' (engine)");
    }

    @Test
    void effectiveCatalogShowsPhraseLanguageOriginTargetAndParameters() throws IOException {
        Files.writeString(workspace.resolve("catalog.yaml"), """
                aliases:
                  - phrase: "{key} of the fake store {source} is {value}"
                    for: "the fake store {source} has {key} set to {value}"
                """);

        List<CatalogEntry> catalog = Engine.catalog(workspace);

        assertThat(catalog).filteredOn(entry -> entry.target().equals("fake.put"))
                .extracting(CatalogEntry::pattern, CatalogEntry::language, CatalogEntry::origin)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                "the fake store {source} has {key} set to {value}", "en", "connector:fake"),
                        org.assertj.core.groups.Tuple.tuple(
                                "lo store finto {source} ha {key} impostato a {value}", "it", "connector:fake"),
                        org.assertj.core.groups.Tuple.tuple(
                                "{key} of the fake store {source} is {value}", "en", "workspace"));
        CatalogEntry exactly = catalog.stream()
                .filter(entry -> entry.pattern().equals("exactly {count} record(s) exist(s) in the fake store {source}"))
                .findFirst().orElseThrow();
        assertThat(exactly.verification()).isEqualTo(Verification.COUNT_EXACTLY);
        assertThat(exactly.parameters()).extracting(parameter -> parameter.name()).containsExactly("count", "source");
        assertThat(catalog).anyMatch(entry -> entry.origin().equals("engine") && entry.language().equals("it"));
    }

    private ExecutionResult run(String feature) throws IOException {
        Files.writeString(workspace.resolve("sources.yaml"), """
                profiles:
                  local:
                    sources:
                      s:
                        type: fake
                """);
        Path file = workspace.resolve("features/catalog.feature");
        Files.createDirectories(file.getParent());
        Files.writeString(file, feature);
        return Engine.run(new ExecutionRequest(workspace, "local", Map.of()));
    }
}
