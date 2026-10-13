package io.github.bdd.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TextAndFileTest {

    @TempDir
    Path workspace;

    @BeforeEach
    void declareTheSources() throws IOException {
        Files.writeString(workspace.resolve("sources.yaml"), """
                profiles:
                  local:
                    sources:
                      shop:
                        type: fake
                      frozen:
                        type: fake
                        readOnly: true
                """);
    }

    @Test
    void docStringOfAWritePhraseIsPassedToTheAction() throws IOException {
        ScenarioResult scenario = run("""
                Given the fake store "shop" holds the record below
                  \"\"\"
                  {"id": 7, "tags": ["a", "b"]}
                  \"\"\"
                Then a record of the fake store "shop" contains the JSON
                  \"\"\"
                  {"id": 7}
                  \"\"\"
                """);

        assertThat(scenario.status()).isEqualTo(Status.PASSED);
    }

    @Test
    void docStringMayCiteVariables() throws IOException {
        ScenarioResult scenario = run("""
                Given the fake store "shop" holds the record below
                  \"\"\"
                  {"id": "${uuid:key}"}
                  \"\"\"
                Then a record exists in the fake store "shop"
                  | field | operator | value   |
                  | id    | equals   | ${key} |
                """);

        assertThat(scenario.status()).isEqualTo(Status.PASSED);
    }

    @Test
    void stepWithoutTheDocStringFailsSayingSo() throws IOException {
        ScenarioResult scenario = run("""
                Given the fake store "shop" holds the record below
                """);

        assertThat(scenario.status()).isEqualTo(Status.FAILED);
        assertThat(scenario.steps().getFirst().error()).contains("doc string");
    }

    @Test
    void fileOfAWritePhraseIsReadRelativeToTheWorkspace() throws IOException {
        Files.createDirectories(workspace.resolve("data"));
        Files.writeString(workspace.resolve("data/order.json"), "{\"id\": 9}");

        ScenarioResult scenario = run("""
                Given the fake store "shop" holds the record of the file "data/order.json"
                Then a record exists in the fake store "shop"
                  | field | operator | value |
                  | id    | equals   | 9     |
                """);

        assertThat(scenario.status()).isEqualTo(Status.PASSED);
    }

    @Test
    void missingFileFailsTheStepNamingIt() throws IOException {
        ScenarioResult scenario = run("""
                Given the fake store "shop" holds the record of the file "data/nope.json"
                """);

        assertThat(scenario.status()).isEqualTo(Status.FAILED);
        assertThat(scenario.steps().getFirst().error()).contains("data/nope.json");
    }

    @Test
    void fileCannotLeaveTheWorkspace() throws IOException {
        ScenarioResult scenario = run("""
                Given the fake store "shop" holds the record of the file "../secret.json"
                """);

        assertThat(scenario.status()).isEqualTo(Status.FAILED);
        assertThat(scenario.steps().getFirst().error()).contains("outside the workspace");
    }

    @Test
    void writeFromTextIsRefusedOnAReadOnlySource() throws IOException {
        ScenarioResult scenario = run("""
                Given the fake store "frozen" holds the record below
                  \"\"\"
                  {"id": 1}
                  \"\"\"
                """);

        assertThat(scenario.status()).isEqualTo(Status.FAILED);
        assertThat(scenario.steps().getFirst().error()).contains("read-only");
    }

    private ScenarioResult run(String steps) throws IOException {
        Path file = workspace.resolve("features/text.feature");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "Feature: Text\n  Scenario: S\n" + steps.indent(4));
        ExecutionResult result = Engine.run(new ExecutionRequest(workspace, "local", Map.of()));
        return result.scenarios().getFirst();
    }
}
