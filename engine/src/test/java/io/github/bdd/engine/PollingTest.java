package io.github.bdd.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PollingTest {

    private static final String PAID = """
              | field  | operator | value |
              | status | equals   | PAID  |
            """;

    @TempDir
    Path workspace;

    /** How long the last execution took, which is no less than its steps did. */
    private Duration elapsed;

    @Test
    void verificationSatisfiedOnlyAfterADelayPasses() throws IOException {
        settings("5s", "20ms");

        assertThat(lastStepOf("""
                Given the fake store "shop" will hold the record '{"status": "PAID"}' after 150 ms
                Then a record exists in the fake store "shop"
                """ + PAID).status()).isEqualTo(Status.PASSED);
    }

    @Test
    void verificationNeverSatisfiedFailsWhenTheTimeoutExpiresAndNotBefore() throws IOException {
        settings("300ms", "20ms");

        assertThat(lastStepOf("""
                Given the fake store "shop" holds the record '{"status": "NEW"}'
                Then a record exists in the fake store "shop"
                """ + PAID).error())
                .startsWith("Expected a matching record, but 0 of the 1 observed records match (after waiting 300 ms)");
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(300));
    }

    @Test
    void verificationIsNotRetriedBeyondTheTimeoutOfTheWorkspace() throws IOException {
        settings("50ms", "20ms");

        assertThat(lastStepOf("""
                Given the fake store "shop" will hold the record '{"status": "PAID"}' after 5000 ms
                Then a record exists in the fake store "shop"
                """).error())
                .startsWith("Expected a matching record, but 0 of the 0 observed records match (after waiting 50 ms)");
    }

    @Test
    void everyKindOfVerificationIsRetried() throws IOException {
        settings("5s", "20ms");
        String delayed = """
                Given the fake store "shop" will hold the record '{"status": "PAID"}' after 150 ms
                """;

        assertThat(lastStepOf(delayed + "Then exactly 1 record exists in the fake store \"shop\"\n").status())
                .isEqualTo(Status.PASSED);
        assertThat(lastStepOf(delayed + "Then at least 1 record exists in the fake store \"shop\"\n").status())
                .isEqualTo(Status.PASSED);
        assertThat(lastStepOf(delayed + """
                Then a record of the fake store "shop" equals the JSON
                  \"""
                  {"status": "PAID"}
                  \"""
                """).status()).isEqualTo(Status.PASSED);
    }

    @Test
    void phraseCanGiveItsOwnTimeout() throws IOException {
        settings("50ms", "20ms");
        String delayed = """
                Given the fake store "shop" will hold the record '{"status": "PAID"}' after 400 ms
                """;

        assertThat(lastStepOf(delayed + "Then a record exists in the fake store \"shop\"\n").status())
                .isEqualTo(Status.FAILED);
        assertThat(lastStepOf(delayed + "Then a record exists in the fake store \"shop\" within 5 seconds\n" + PAID)
                .status()).isEqualTo(Status.PASSED);
        assertThat(lastStepOf(delayed + "Then a record exists in the fake store \"shop\" within -1 seconds\n")
                .error()).isEqualTo("The timeout of a step cannot be negative, but was -1 seconds");
    }

    @Test
    void absenceVerificationWaitsTheWholeWindowBeforePassing() throws IOException {
        // A first execution, so that starting the engine is not what takes the time measured below.
        lastStepOf("When I wait 0 seconds\n");
        settings("300ms", "20ms");

        assertThat(lastStepOf("""
                Given the fake store "shop" holds the record '{"status": "NEW"}'
                Then no record exists in the fake store "shop"
                """ + PAID).status()).isEqualTo(Status.PASSED);
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(300));
    }

    @Test
    void absenceVerificationFailsIfTheDataAppearsDuringTheWindow() throws IOException {
        settings("30s", "20ms");
        String delayed = """
                Given the fake store "shop" will hold the record '{"status": "PAID"}' after 150 ms
                """;

        assertThat(lastStepOf(delayed + "Then no record exists in the fake store \"shop\"\n" + PAID).error())
                .startsWith("Expected no matching record, but 1 of the 1 observed records match");
        // The step fails as soon as the record appears, without waiting for the window to end.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(30));
        assertThat(lastStepOf(delayed + "Then at most 0 records exist in the fake store \"shop\"\n").error())
                .startsWith("Expected at most 0 matching records, but 1 of the 1 observed records match");
    }

    @Test
    void phraseCanGiveTheWindowOfAnAbsenceVerification() throws IOException {
        settings("50ms", "20ms");
        String delayed = """
                Given the fake store "shop" will hold the record '{"status": "PAID"}' after 400 ms
                """;

        assertThat(lastStepOf(delayed + "Then no record exists in the fake store \"shop\"\n").status())
                .isEqualTo(Status.PASSED);
        assertThat(lastStepOf(delayed + "Then no record exists in the fake store \"shop\" for 5 seconds\n").error())
                .startsWith("Expected no matching record, but 1 of the 1 observed records match");
    }

    @Test
    void stepThatIsNotWrittenCorrectlyFailsWithoutBeingRetried() throws IOException {
        settings("30s", "20ms");

        assertThat(lastStepOf("""
                Then a record exists in the fake store "shop"
                  | field  | operator | value |
                  | status | above    | PAID  |
                """).error()).startsWith("features/orders.feature:6: unknown operator 'above'");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    void explicitWaitLastsTheGivenSeconds() throws IOException {
        assertThat(lastStepOf("When I wait 1 second\n").status()).isEqualTo(Status.PASSED);
        // Short of a second by what the clock of the platform may round off.
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(950));

        assertThat(lastStepOf("When I wait 0 seconds\n").status()).isEqualTo(Status.PASSED);
        assertThat(lastStepOf("Quando attendo 0 secondi\n").status()).isEqualTo(Status.PASSED);
        assertThat(lastStepOf("Quando attendo 0 secondo\n").status()).isEqualTo(Status.PASSED);
        assertThat(lastStepOf("When I wait -1 seconds\n").error())
                .isEqualTo("A wait cannot be negative, but was -1 seconds");
    }

    @Test
    void workspaceWithoutSettingsUsesTheDefaults() throws IOException {
        assertThat(lastStepOf("""
                Given the fake store "shop" will hold the record '{"status": "PAID"}' after 100 ms
                Then a record exists in the fake store "shop"
                """).status()).isEqualTo(Status.PASSED);
    }

    @Test
    void settingsMayLeaveOutWhatKeepsItsDefault() throws IOException {
        Files.writeString(workspace.resolve("settings.yaml"), """
                verification:
                  timeout: 0ms
                """);

        assertThat(lastStepOf("Then a record exists in the fake store \"shop\"\n").error())
                .startsWith("Expected a matching record, but 0 of the 0 observed records match");
    }

    @Test
    void invalidSettingsAreRejectedBeforeRunning() throws IOException {
        assertThatSettingsAreRejected("""
                verification:
                  timeout: soon
                """, "Invalid settings.yaml: verification.timeout: "
                + "expected a duration such as 10s or 500ms but was 'soon'");
        assertThatSettingsAreRejected("""
                verification:
                  timeout: 10
                """, "Invalid settings.yaml: verification.timeout: "
                + "expected a duration such as 10s or 500ms but was 10");
        assertThatSettingsAreRejected("""
                verification:
                  pollInterval: 0ms
                """, "Invalid settings.yaml: verification.pollInterval: expected more than zero but was '0ms'");
        assertThatSettingsAreRejected("""
                verification:
                  retries: 3
                """, "Invalid settings.yaml: verification.retries: unknown property; allowed: timeout, pollInterval");
        assertThatSettingsAreRejected("""
                polling: true
                """, "Invalid settings.yaml: polling: unknown property; allowed: verification, diagnostics, reports");
    }

    private void assertThatSettingsAreRejected(String settings, String message) throws IOException {
        Files.writeString(workspace.resolve("settings.yaml"), settings);
        assertThatThrownBy(() -> lastStepOf("When I wait 0 seconds\n"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage(message);
    }

    private void settings(String timeout, String pollInterval) throws IOException {
        Files.writeString(workspace.resolve("settings.yaml"), """
                verification:
                  timeout: %s
                  pollInterval: %s
                """.formatted(timeout, pollInterval));
    }

    /** Runs a scenario made of the given steps, which start at line 4 of the feature; returns its last step. */
    private StepResult lastStepOf(String steps) throws IOException {
        Files.writeString(workspace.resolve("sources.yaml"), """
                profiles:
                  local:
                    sources:
                      shop:
                        type: fake
                """);
        Path file = workspace.resolve("features/orders.feature");
        Files.createDirectories(file.getParent());
        String header = steps.startsWith("Quando")
                ? "# language: it\nFunzionalità: Ordini\n  Scenario: Ordini\n"
                : "# language: en\nFeature: Orders\n  Scenario: Orders\n";
        Files.writeString(file, header + steps.indent(4));

        long start = System.nanoTime();
        ExecutionResult result = Engine.run(new ExecutionRequest(workspace, "local", Map.of()));
        elapsed = Duration.ofNanos(System.nanoTime() - start);
        return result.scenarios().getFirst().steps().getLast();
    }
}
