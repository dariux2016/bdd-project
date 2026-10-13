package io.github.bdd.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiagnosticsTest {

    private static final String PAID = """
              | field  | operator | value |
              | status | equals   | PAID  |
            """;

    private static final String MASKING_SOURCES = """
            profiles:
              local:
                sources:
                  shop:
                    type: fake
                    mask:
                      - password
                      - customer.card
                      - $.lines[*].secret
            """;

    @TempDir
    Path workspace;

    @Test
    void failedVerificationShowsTheObservedRecords() throws IOException {
        assertThat(errorOf("", """
                Given the fake store "shop" holds the record '{"id": 1, "status": "NEW"}'
                And the fake store "shop" holds the record '{"id": 2, "status": "SHIPPED"}'
                Then a record exists in the fake store "shop"
                """ + PAID)).isEqualTo("""
                Expected a matching record, but 0 of the 2 observed records match
                Observed records:
                  {"id":1,"status":"NEW"}
                  {"id":2,"status":"SHIPPED"}""");
    }

    @Test
    void countFailureShowsTheExpectedAndTheFoundNumberWithTheObservedRecords() throws IOException {
        assertThat(errorOf("", """
                Given the fake store "shop" holds the record '{"id": 1, "status": "PAID"}'
                Then exactly 2 records exist in the fake store "shop"
                """ + PAID)).isEqualTo("""
                Expected exactly 2 matching records, but 1 of the 1 observed records match
                Observed records:
                  {"id":1,"status":"PAID"}""");
    }

    @Test
    void sourceThatReturnsNothingIsSaidSo() throws IOException {
        assertThat(errorOf("", """
                Then a record exists in the fake store "shop"
                """)).isEqualTo("""
                Expected a matching record, but 0 of the 0 observed records match
                Observed records: none""");
    }

    @Test
    void jsonFailureShowsTheDifferenceAndTheObservedRecords() throws IOException {
        assertThat(errorOf("", """
                Given the fake store "shop" holds the record '{"id": 1, "status": "NEW"}'
                Then a record of the fake store "shop" contains the JSON
                  \"""
                  {"status": "PAID"}
                  \"""
                """)).isEqualTo("""
                Expected the observed record to contain the JSON, but at $.status: expected "PAID" but was "NEW"
                Observed records:
                  {"id":1,"status":"NEW"}""");
    }

    @Test
    void timeoutIsToldOnTheFirstLineAheadOfTheObservedRecords() throws IOException {
        assertThat(errorOf("""
                verification:
                  timeout: 100ms
                  pollInterval: 20ms
                """, """
                Given the fake store "shop" holds the record '{"id": 1, "status": "NEW"}'
                Then a record exists in the fake store "shop"
                """ + PAID)).isEqualTo("""
                Expected a matching record, but 0 of the 1 observed records match (after waiting 100 ms)
                Observed records:
                  {"id":1,"status":"NEW"}""");
    }

    @Test
    void observedRecordsAreCutAtTheConfiguredNumberAndTheOmittedOnesAreCounted() throws IOException {
        assertThat(errorOf("""
                diagnostics:
                  maxRecords: 2
                """, """
                Given the fake store "shop" holds the record '{"id": 1}'
                And the fake store "shop" holds the record '{"id": 2}'
                And the fake store "shop" holds the record '{"id": 3}'
                And the fake store "shop" holds the record '{"id": 4}'
                Then no record exists in the fake store "shop"
                """)).isEqualTo("""
                Expected no matching record, but 4 of the 4 observed records match
                Observed records:
                  {"id":1}
                  {"id":2}
                  (2 more records omitted)""");
    }

    @Test
    void observedRecordsAreCutAtTheConfiguredSizeKeepingOnlyWholeRecords() throws IOException {
        assertThat(errorOf("""
                diagnostics:
                  maxSize: 20
                """, """
                Given the fake store "shop" holds the record '{"id": 1}'
                And the fake store "shop" holds the record '{"id": 2}'
                And the fake store "shop" holds the record '{"id": 3, "note": "far too long"}'
                Then exactly 0 records exist in the fake store "shop"
                """)).isEqualTo("""
                Expected exactly 0 matching records, but 3 of the 3 observed records match
                Observed records:
                  {"id":1}
                  {"id":2}
                  (1 more record omitted)""");
    }

    @Test
    void maskedFieldsAreHiddenWhereverTheRecordsAreShown() throws IOException {
        String record = """
                Given the fake store "shop" holds the record '{"id": 1, "status": "NEW", "password": "hunter2", "customer": {"name": "Ann", "card": "4111"}, "lines": [{"secret": "s1", "sku": "A"}, {"secret": "s2", "sku": "B"}]}'
                """;
        String masked = """
                {"id":1,"status":"NEW","password":"***","customer":{"name":"Ann","card":"***"},"lines":[{"secret":"***","sku":"A"},{"secret":"***","sku":"B"}]}""";

        assertThat(errorOf("", MASKING_SOURCES, record + """
                Then a record exists in the fake store "shop"
                """ + PAID)).isEqualTo("""
                Expected a matching record, but 0 of the 1 observed records match
                Observed records:
                """ + "  " + masked);

        // The difference quotes the record, so it must not give the value of a masked field away.
        assertThat(errorOf("", MASKING_SOURCES, record + """
                Then a record of the fake store "shop" equals the JSON
                  \"""
                  {"id": 1, "status": "NEW", "customer": {"name": "Ann"}, "lines": [{"sku": "A"}, {"sku": "B"}]}
                  \"""
                """)).isEqualTo("""
                Expected the observed record to equal the JSON, but at $.customer.card: unexpected field with value "***"
                Observed records:
                """ + "  " + masked);
    }

    @Test
    void maskedFieldThatMatchesTheExpectedJsonIsNotBlamedForAnotherDifference() throws IOException {
        assertThat(errorOf("", MASKING_SOURCES, """
                Given the fake store "shop" holds the record '{"password": "hunter2", "status": "NEW"}'
                Then a record of the fake store "shop" contains the JSON
                  \"""
                  {"password": "hunter2", "status": "PAID"}
                  \"""
                """)).isEqualTo("""
                Expected the observed record to contain the JSON, but at $.status: expected "PAID" but was "NEW"
                Observed records:
                  {"password":"***","status":"NEW"}""");
    }

    @Test
    void maskCanReachAFieldAtAnyDepthAndMaskingTheWholeRecordHidesIt() throws IOException {
        String record = """
                Given the fake store "shop" holds the record '{"a": {"password": "p1"}, "b": [{"password": "p2"}]}'
                """;
        assertThat(errorOf("", sources("$..password"), record + """
                Then no record exists in the fake store "shop"
                """)).endsWith("""
                  {"a":{"password":"***"},"b":[{"password":"***"}]}""");
        assertThat(errorOf("", sources("$"), record + """
                Then no record exists in the fake store "shop"
                """)).endsWith("  \"***\"").doesNotContain("p1", "p2");
    }

    @Test
    void observedValueInADifferenceIsCutAtTheConfiguredSize() throws IOException {
        assertThat(errorOf("""
                diagnostics:
                  maxSize: 40
                """, """
                Given the fake store "shop" holds the record '{"note": "%s"}'
                Then a record of the fake store "shop" equals the JSON
                  \"""
                  {"note": "short"}
                  \"""
                """.formatted("x".repeat(500)))).doesNotContain("x".repeat(41));
    }

    private static String sources(String mask) {
        return """
                profiles:
                  local:
                    sources:
                      shop:
                        type: fake
                        mask: ["%s"]
                """.formatted(mask);
    }

    @Test
    void maskingDoesNotChangeWhatTheVerificationCompares() throws IOException {
        assertThat(run("", MASKING_SOURCES, """
                Given the fake store "shop" holds the record '{"id": 1, "password": "hunter2"}'
                Then a record exists in the fake store "shop"
                  | field    | operator | value   |
                  | password | equals   | hunter2 |
                """).status()).isEqualTo(Status.PASSED);
    }

    @Test
    void invalidDiagnosticsSettingsAreRejectedBeforeRunning() {
        assertThatSettingsAreRejected("""
                diagnostics:
                  maxRecords: -1
                """, "Invalid settings.yaml: diagnostics.maxRecords: expected a number of at least zero but was -1");
        assertThatSettingsAreRejected("""
                diagnostics:
                  maxSize: lots
                """, "Invalid settings.yaml: diagnostics.maxSize: expected a number of at least zero but was 'lots'");
        assertThatSettingsAreRejected("""
                diagnostics:
                  records: 3
                """, "Invalid settings.yaml: diagnostics.records: unknown property; allowed: maxRecords, maxSize");
    }

    @Test
    void invalidMaskIsRejectedBeforeRunning() {
        assertThatSourcesAreRejected("""
                profiles:
                  local:
                    sources:
                      shop:
                        type: fake
                        mask: password
                """, "Invalid sources.yaml: profiles.local.sources.shop.mask: "
                + "expected a list of fields but was 'password'");
        assertThatSourcesAreRejected("""
                profiles:
                  local:
                    sources:
                      shop:
                        type: fake
                        mask: [ok, 3]
                """, "Invalid sources.yaml: profiles.local.sources.shop.mask[1]: "
                + "expected a field as a JSONPath but was 3");
        assertThatSourcesAreRejected("""
                profiles:
                  local:
                    sources:
                      shop:
                        type: fake
                        mask: ["$.a."]
                """, "Invalid sources.yaml: profiles.local.sources.shop.mask[0]: '$.a.' is not a JSONPath: ");
    }

    private void assertThatSettingsAreRejected(String settings, String message) {
        assertThatThrownBy(() -> run(settings, null, "When I wait 0 seconds\n"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage(message);
    }

    private void assertThatSourcesAreRejected(String sources, String messageStart) {
        assertThatThrownBy(() -> run("", sources, "When I wait 0 seconds\n"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageStartingWith(messageStart);
    }

    private String errorOf(String settings, String steps) throws IOException {
        return errorOf(settings, null, steps);
    }

    private String errorOf(String settings, String sources, String steps) throws IOException {
        StepResult step = run(settings, sources, steps);
        assertThat(step.status()).isEqualTo(Status.FAILED);
        return step.error();
    }

    /** Runs a scenario made of the given steps, which start at line 3 of the feature; returns its last step. */
    private StepResult run(String settings, String sources, String steps) throws IOException {
        Files.writeString(workspace.resolve("sources.yaml"), sources != null ? sources : """
                profiles:
                  local:
                    sources:
                      shop:
                        type: fake
                """);
        // Unless a test sets the timeout, a failing verification is not retried.
        Files.writeString(workspace.resolve("settings.yaml"),
                settings.contains("verification:") ? settings : "verification:\n  timeout: 0ms\n" + settings);
        Path file = workspace.resolve("features/orders.feature");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "Feature: Orders\n  Scenario: Orders\n" + steps.indent(4));

        ExecutionResult result = Engine.run(new ExecutionRequest(workspace, "local", Map.of()));
        return result.scenarios().getFirst().steps().getLast();
    }
}
