package io.github.bdd.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationTest {

    /** Lines 3 to 5 of the feature; the steps of each test start at line 6. */
    private static final String ORDERS = """
            Given the fake store "shop" holds the record '{"id": 1, "status": "NEW", "total": 9.5}'
            And the fake store "shop" holds the record '{"id": 2, "status": "PAID", "total": 120}'
            And the fake store "shop" holds the record '{"id": 3, "status": "PAID", "total": 30, "note": null}'
            """;

    @TempDir
    Path workspace;

    @Test
    void recordSatisfyingEveryRowOfTheFilterExists() throws IOException {
        assertThat(statusOf("""
                Then a record exists in the fake store "shop"
                  | field  | operator | value |
                  | status | equals   | PAID  |
                  | id     | equals   | 3     |
                """)).isEqualTo(Status.PASSED);
    }

    @Test
    void existenceFailsWhenNoRecordSatisfiesEveryRow() throws IOException {
        assertThat(errorOf("""
                Then a record exists in the fake store "shop"
                  | field  | operator | value |
                  | status | equals   | NEW   |
                  | id     | equals   | 3     |
                """)).isEqualTo("Expected a matching record, but 0 of the 3 observed records match");
    }

    @Test
    void nonExistencePassesOnlyWhenNoRecordSatisfiesTheFilter() throws IOException {
        assertThat(statusOf("""
                Then no record exists in the fake store "shop"
                  | field  | operator | value     |
                  | status | equals   | CANCELLED |
                """)).isEqualTo(Status.PASSED);
        assertThat(errorOf("""
                Then no record exists in the fake store "shop"
                  | field  | operator | value |
                  | status | equals   | PAID  |
                """)).isEqualTo("Expected no matching record, but 2 of the 3 observed records match");
    }

    @Test
    void withoutATableEveryRecordCounts() throws IOException {
        assertThat(statusOf("""
                Then exactly 3 records exist in the fake store "shop"
                """)).isEqualTo(Status.PASSED);
        assertThat(errorOf("""
                Then no record exists in the fake store "shop"
                """)).isEqualTo("Expected no matching record, but 3 of the 3 observed records match");
    }

    @Test
    void countsCompareTheNumberOfRecordsSatisfyingTheFilter() throws IOException {
        String paid = """
                  | field  | operator | value |
                  | status | equals   | PAID  |
                """;
        assertThat(statusOf("Then exactly 2 records exist in the fake store \"shop\"\n" + paid))
                .isEqualTo(Status.PASSED);
        assertThat(statusOf("Then at least 2 records exist in the fake store \"shop\"\n" + paid))
                .isEqualTo(Status.PASSED);
        assertThat(statusOf("Then at most 2 records exist in the fake store \"shop\"\n" + paid))
                .isEqualTo(Status.PASSED);

        assertThat(errorOf("Then exactly 1 record exists in the fake store \"shop\"\n" + paid))
                .isEqualTo("Expected exactly 1 matching record, but 2 of the 3 observed records match");
        assertThat(errorOf("Then at least 3 records exist in the fake store \"shop\"\n" + paid))
                .isEqualTo("Expected at least 3 matching records, but 2 of the 3 observed records match");
        assertThat(errorOf("Then at most 1 record exists in the fake store \"shop\"\n" + paid))
                .isEqualTo("Expected at most 1 matching record, but 2 of the 3 observed records match");
    }

    @Test
    void operatorsSelectTheExpectedRecords() throws IOException {
        assertThat(idsMatching("status | equals       | PAID")).containsExactly(2, 3);
        assertThat(idsMatching("status | not equals   | PAID")).containsExactly(1);
        assertThat(idsMatching("status | contains     | AI")).containsExactly(2, 3);
        assertThat(idsMatching("status | regex        | ^(NEW\\|P.+D)$")).containsExactly(1, 2, 3);
        assertThat(idsMatching("status | regex        | ^N")).containsExactly(1);
        assertThat(idsMatching("total  | greater than | 30")).containsExactly(2);
        assertThat(idsMatching("total  | less than    | 30")).containsExactly(1);
        assertThat(idsMatching("note   | is null      |")).containsExactly(1, 2, 3);
        assertThat(idsMatching("status | is null      |")).isEmpty();
        assertThat(idsMatching("status | is not null  |")).containsExactly(1, 2, 3);
        assertThat(idsMatching("note   | is not null  |")).isEmpty();
    }

    @Test
    void operatorsHaveSymbolsAndItalianNames() throws IOException {
        assertThat(idsMatching("status | =  | PAID")).containsExactly(2, 3);
        assertThat(idsMatching("status | != | PAID")).containsExactly(1);
        assertThat(idsMatching("total  | >  | 30")).containsExactly(2);
        assertThat(idsMatching("total  | <  | 30")).containsExactly(1);

        assertThat(statusOf("""
                Then exactly 1 record exists in the fake store "shop"
                  | campo  | operatore   | valore |
                  | status | uguale      | PAID   |
                  | status | diverso     | NEW    |
                  | status | contiene    | AI     |
                  | total  | maggiore    | 20     |
                  | total  | minore      | 100    |
                  | note   | è nullo     |        |
                  | id     | non è nullo |        |
                """)).isEqualTo(Status.PASSED);
    }

    @Test
    void numbersAreComparedAsNumbers() throws IOException {
        // As text, "9.5" would be greater than "120" and "30" different from "30.0".
        assertThat(idsMatching("total | greater than | 100")).containsExactly(2);
        assertThat(idsMatching("total | less than    | 10")).containsExactly(1);
        assertThat(idsMatching("total | equals       | 30.0")).containsExactly(3);
        assertThat(idsMatching("total | not equals   | 9.50")).containsExactly(2, 3);
        assertThat(idsMatching("total | greater than | plenty")).isEmpty();
    }

    @Test
    void datesAreComparedAsInstants() throws IOException {
        String events = """
                Given the fake store "shop" holds the record '{"id": 10, "at": "2024-03-10T09:00:00+02:00"}'
                And the fake store "shop" holds the record '{"id": 11, "at": "2024-03-10T08:30:00Z"}'
                And the fake store "shop" holds the record '{"id": 12, "at": "2024-03-09"}'
                """;
        // As text, 09:00+02:00 would follow 08:30Z, which it precedes by an hour and a half.
        assertThat(idsMatching(events, "at | less than    | 2024-03-10T08:00:00Z")).containsExactly(10, 12);
        assertThat(idsMatching(events, "at | greater than | 2024-03-10T08:00:00Z")).containsExactly(11);
        assertThat(idsMatching(events, "at | equals       | 2024-03-10T07:00:00Z")).containsExactly(10);
        assertThat(idsMatching(events, "at | greater than | 2024-03-09")).containsExactly(10, 11);
    }

    @Test
    void nestedFieldsAreReachedWithJsonPath() throws IOException {
        String order = """
                Given the fake store "shop" holds the record '{"id": 20, "customer": {"name": "Ann", "tags": ["vip", "new"]}, "lines": [{"sku": "A1", "qty": 2}, {"sku": "B2", "qty": 5}]}'
                """;
        assertThat(idsMatching(order, "$.customer.name     | equals       | Ann")).containsExactly(20);
        assertThat(idsMatching(order, "customer.name       | equals       | Bob")).isEmpty();
        assertThat(idsMatching(order, "$.lines[1].qty      | greater than | 4")).containsExactly(20);
        assertThat(idsMatching(order, "$.lines[0].sku      | equals       | B2")).isEmpty();
        assertThat(idsMatching(order, "$.customer.tags     | contains     | vip")).containsExactly(20);
        assertThat(idsMatching(order, "$.lines[*].sku      | contains     | B2")).containsExactly(20);
        assertThat(idsMatching(order, "$.lines[*].sku      | contains     | C3")).isEmpty();
        assertThat(idsMatching(order, "$.customer.address  | is null      |")).containsExactly(1, 2, 3, 20);
        assertThat(idsMatching(order, "$.lines[*].sku      | is not null  |")).containsExactly(20);
    }

    @Test
    void unknownOperatorIsAnErrorCitingTheRow() throws IOException {
        assertThat(errorOf("""
                Then a record exists in the fake store "shop"
                  | field  | operator | value |
                  | status | equals   | PAID  |
                  # a comment between the rows
                  | id     | above    | 2     |
                """))
                .startsWith("features/orders.feature:10: unknown operator 'above'; the operators are: equals, =, uguale,");
    }

    @Test
    void malformedTableIsAnErrorCitingTheRow() throws IOException {
        assertThat(errorOf("""
                Then a record exists in the fake store "shop"
                  | status | equals | PAID |
                """)).startsWith("features/orders.feature:7: a filter table has the columns 'field | operator | value'");
        assertThat(errorOf("""
                Then a record exists in the fake store "shop"
                  | field  | value |
                  | status | PAID  |
                """)).startsWith("features/orders.feature:7: a filter table has the columns 'field | operator | value'");
        assertThat(errorOf("""
                Then a record exists in the fake store "shop"
                  | field  | operator | value |
                  | status | equals   | PAID  |
                  |        | equals   | PAID  |
                """)).isEqualTo("features/orders.feature:9: the field is missing");
        assertThat(errorOf("""
                Then a record exists in the fake store "shop"
                  | field  | operator     | value |
                  | total  | greater than |       |
                """)).isEqualTo("features/orders.feature:8: operator 'greater than' needs a value");
        assertThat(errorOf("""
                Then a record exists in the fake store "shop"
                  | field  | operator | value |
                  | status | is null  | PAID  |
                """)).isEqualTo("features/orders.feature:8: operator 'is null' takes no value, but 'PAID' was given");
        assertThat(errorOf("""
                Then a record exists in the fake store "shop"
                  | field  | operator | value |
                  | status | regex    | (PAID |
                """)).startsWith("features/orders.feature:8: '(PAID' is not a regular expression");
        assertThat(errorOf("""
                Then a record exists in the fake store "shop"
                  | field       | operator | value |
                  | $.customer. | equals   | PAID  |
                """)).startsWith("features/orders.feature:8: '$.customer.' is not a JSONPath");
    }

    @Test
    void strictJsonComparisonRequiresTheSameFieldsAndValues() throws IOException {
        assertThat(statusOf("""
                Then a record of the fake store "shop" equals the JSON
                  \"""
                  {"total": 120.0, "status": "PAID", "id": 2}
                  \"""
                """)).isEqualTo(Status.PASSED);
        assertThat(errorOf("""
                Then a record of the fake store "shop" equals the JSON
                  \"""
                  {"id": 2, "status": "PAID"}
                  \"""
                """)).isEqualTo("Expected a record to equal the JSON, but none of the 3 observed records does");
    }

    @Test
    void partialJsonComparisonAllowsFurtherFields() throws IOException {
        assertThat(statusOf("""
                Then a record of the fake store "shop" contains the JSON
                  \"""
                  {"id": 2, "status": "PAID"}
                  \"""
                """)).isEqualTo(Status.PASSED);
        assertThat(errorOf("""
                Then a record of the fake store "shop" contains the JSON
                  \"""
                  {"id": 2, "status": "NEW"}
                  \"""
                """)).isEqualTo("Expected a record to contain the JSON, but none of the 3 observed records does");
    }

    @Test
    void jsonComparisonDescendsIntoNestedStructures() throws IOException {
        String order = """
                Given the fake store "cart" holds the record '{"id": 20, "customer": {"name": "Ann", "age": 41}, "lines": [{"sku": "A1", "qty": 2}, {"sku": "B2", "qty": 5}]}'
                """;
        assertThat(lastStepOf(order + """
                Then a record of the fake store "cart" contains the JSON
                  \"""
                  {"customer": {"name": "Ann"}, "lines": [{"sku": "A1"}, {"qty": 5}]}
                  \"""
                """).status()).isEqualTo(Status.PASSED);
        assertThat(lastStepOf(order + """
                Then a record of the fake store "cart" contains the JSON
                  \"""
                  {"customer": {"name": "Ann"}, "lines": [{"sku": "A1"}, {"qty": 6}]}
                  \"""
                """).error()).isEqualTo(
                "Expected the observed record to contain the JSON, but at $.lines[1].qty: expected 6 but was 5");
        assertThat(lastStepOf(order + """
                Then a record of the fake store "cart" equals the JSON
                  \"""
                  {"id": 20, "customer": {"name": "Ann"}, "lines": [{"sku": "A1", "qty": 2}, {"sku": "B2", "qty": 5}]}
                  \"""
                """).error()).isEqualTo(
                "Expected the observed record to equal the JSON, but at $.customer.age: unexpected field with value 41");
        assertThat(lastStepOf(order + """
                Then a record of the fake store "cart" contains the JSON
                  \"""
                  {"lines": [{"sku": "A1"}]}
                  \"""
                """).error()).isEqualTo(
                "Expected the observed record to contain the JSON, but at $.lines: expected 1 elements but was 2");
    }

    @Test
    void jsonComparisonLeavesOutTheIgnoredFields() throws IOException {
        String order = """
                Given the fake store "cart" holds the record '{"id": 20, "createdAt": "2024-03-10T09:00:00Z", "lines": [{"id": 7, "sku": "A1"}, {"id": 8, "sku": "B2"}]}'
                """;
        assertThat(lastStepOf(order + """
                Then a record of the fake store "cart" equals the JSON ignoring "id, createdAt, $.lines[0,1].id"
                  \"""
                  {"id": "any", "lines": [{"sku": "A1"}, {"sku": "B2"}]}
                  \"""
                """).status()).isEqualTo(Status.PASSED);
        assertThat(lastStepOf(order + """
                Then a record of the fake store "cart" equals the JSON ignoring "createdAt"
                  \"""
                  {"id": 20, "lines": [{"sku": "A1"}, {"sku": "B2"}]}
                  \"""
                """).error()).isEqualTo(
                "Expected the observed record to equal the JSON, but at $.lines[0].id: unexpected field with value 7");
        assertThat(lastStepOf(order + """
                Then a record of the fake store "cart" contains the JSON ignoring "$..id"
                  \"""
                  {"id": 99, "lines": [{"id": 1, "sku": "A1"}, {"id": 2, "sku": "B2"}]}
                  \"""
                """).status()).isEqualTo(Status.PASSED);
    }

    @Test
    void stepNotWrittenAsItsVerificationRequiresIsAnError() throws IOException {
        assertThat(errorOf("""
                Then a record of the fake store "shop" equals the JSON
                """)).isEqualTo("This step needs the expected JSON as a doc string");
        assertThat(errorOf("""
                Then a record of the fake store "shop" equals the JSON
                  \"""
                  {"id": 2,
                  \"""
                """)).startsWith("The expected JSON is not valid: ");
        assertThat(errorOf("""
                Then a record exists in the fake store "shop"
                  \"""
                  {"id": 2}
                  \"""
                """)).isEqualTo("This step takes a filter table, not a doc string");
    }

    /** The ids of the orders, and of the records the given steps add, that satisfy one filter row. */
    private List<Integer> idsMatching(String row) throws IOException {
        return idsMatching("", row);
    }

    private List<Integer> idsMatching(String givenSteps, String row) throws IOException {
        List<Integer> ids = new ArrayList<>();
        for (int id : List.of(1, 2, 3, 10, 11, 12, 20)) {
            Status status = statusOf(givenSteps + """
                    Then a record exists in the fake store "shop"
                      | field | operator | value |
                      | id    | equals   | %d    |
                      | %s |
                    """.formatted(id, row));
            if (status == Status.PASSED) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** Runs a scenario made of the three orders followed by the given steps; returns its last step. */
    private StepResult lastStepOf(String steps) throws IOException {
        Files.writeString(workspace.resolve("sources.yaml"), """
                profiles:
                  local:
                    sources:
                      shop:
                        type: fake
                      cart:
                        type: fake
                """);
        // The records are there from the start: a verification that fails need not be retried.
        Files.writeString(workspace.resolve("settings.yaml"), """
                verification:
                  timeout: 0ms
                """);
        Path file = workspace.resolve("features/orders.feature");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "Feature: Orders\n  Scenario: Orders\n" + (ORDERS + steps).indent(4));

        ExecutionResult result = Engine.run(new ExecutionRequest(workspace, "local", Map.of()));
        return result.scenarios().getFirst().steps().getLast();
    }

    private Status statusOf(String steps) throws IOException {
        return lastStepOf(steps).status();
    }

    private String errorOf(String steps) throws IOException {
        StepResult step = lastStepOf(steps);
        assertThat(step.status()).isEqualTo(Status.FAILED);
        return step.error();
    }
}
