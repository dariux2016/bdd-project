package io.github.bdd.connector.postgresql;

import io.github.bdd.engine.Engine;
import io.github.bdd.engine.ExecutionRequest;
import io.github.bdd.engine.ExecutionResult;
import io.github.bdd.engine.LocalContainers;
import io.github.bdd.engine.ScenarioResult;
import io.github.bdd.engine.Selection;
import io.github.bdd.engine.Status;
import io.github.bdd.plugin.Action;
import io.github.bdd.plugin.Arguments;
import io.github.bdd.plugin.Source;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs against a real PostgreSQL in a container, so it is skipped where Docker is not available. */
class PostgresConnectorTest {

    private static final String IMAGE = "postgres:16-alpine";

    private static LocalContainers containers;

    @TempDir
    Path workspace;

    @BeforeAll
    static void startPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        containers = new LocalContainers();
    }

    @AfterAll
    static void stopPostgres() {
        if (containers != null) {
            containers.close();
        }
    }

    @Test
    void exampleWorkspaceRunsOnAContainerStartedFromTheDeclaredImage() throws IOException {
        copy(Path.of("..", "examples", "postgresql"), workspace);

        ExecutionResult result = run();

        assertThat(result.scenarios()).extracting(ScenarioResult::name).containsExactlyInAnyOrder(
                "Rows from a table are found with the unified filter", "Column types are reported as JSON types",
                "Rows from files", "Native queries verify and capture values",
                "Righe da tabella e verifiche con filtro", "Query nativa");
        assertThat(result.scenarios()).allSatisfy(scenario -> assertThat(scenario.status())
                .describedAs("%s: %s", scenario.name(), scenario.steps())
                .isEqualTo(Status.PASSED));
        assertThat(result.successful()).isTrue();
    }

    @Test
    void writesAreBlockedOnAReadOnlySourceButItsDataCanBeVerified() throws IOException {
        // Both sources are the same container: the engine starts one per type and image.
        Files.writeString(workspace.resolve("sources.yaml"), """
                profiles:
                  local:
                    sources:
                      shop:
                        type: postgresql
                        image: %1$s
                      mirror:
                        type: postgresql
                        image: %1$s
                        readOnly: true
                """.formatted(IMAGE));
        feature("""
                Feature: Read only
                  Scenario: Writing to the source that is not read only
                    Given the script is executed on the PostgreSQL source "shop"
                      \"\"\"
                      CREATE TABLE IF NOT EXISTS pets (name text);
                      DELETE FROM pets;
                      INSERT INTO pets VALUES ('Rex');
                      \"\"\"

                  Scenario: Reading it through the read only one
                    Then a row exists in table "pets" of the PostgreSQL source "mirror"
                      | field | operator | value |
                      | name  | equals   | Rex   |

                  Scenario: Emptying a table
                    When the table "pets" of the PostgreSQL source "mirror" is emptied

                  Scenario: Inserting rows
                    When these rows are inserted in table "pets" of the PostgreSQL source "mirror"
                      | name |
                      | Tom  |

                  Scenario: Running a script
                    When the script is executed on the PostgreSQL source "mirror"
                      \"\"\"
                      DELETE FROM pets
                      \"\"\"

                  Scenario: Verifying the data was not touched
                    Then exactly 1 row exists in table "pets" of the PostgreSQL source "shop"
                """);

        List<ScenarioResult> scenarios = run().scenarios();

        assertThat(scenarios).extracting(ScenarioResult::status).containsExactly(
                Status.PASSED, Status.PASSED, Status.FAILED, Status.FAILED, Status.FAILED, Status.PASSED);
        assertThat(scenarios.get(2).steps().getFirst().error()).contains("is read-only");
    }

    @Test
    void queryCannotModifyTheDatabaseEvenOnASourceThatAllowsWrites() throws IOException {
        Files.writeString(workspace.resolve("sources.yaml"), """
                profiles:
                  local:
                    sources:
                      shop:
                        type: postgresql
                        image: %s
                """.formatted(IMAGE));
        feature("""
                Feature: Queries
                  Scenario: A query that writes
                    Given the script is executed on the PostgreSQL source "shop"
                      \"\"\"
                      CREATE TABLE IF NOT EXISTS pets (name text);
                      \"\"\"
                    Then the query "INSERT INTO pets VALUES ('Rex') RETURNING name" on the PostgreSQL source "shop" returns a row within 0 seconds
                """);

        ScenarioResult scenario = run().scenarios().getFirst();

        assertThat(scenario.status()).isEqualTo(Status.FAILED);
        assertThat(scenario.steps().getLast().error()).contains("read-only transaction");
    }

    @Test
    void columnTypesAreReportedAsJsonTypes() throws Exception {
        PostgresConnector connector = new PostgresConnector();
        try (var container = connector.startContainer(IMAGE)) {
            Source source = new Source("shop", container.config());
            Database.script(source, """
                    CREATE TABLE typed (
                        id bigint, small smallint, price numeric(10, 2), ratio double precision,
                        active boolean, born date, seen timestamp, placed timestamptz, at time,
                        id_text uuid, doc jsonb, tags text[], numbers integer[], note text, raw bytea);
                    INSERT INTO typed VALUES (
                        9000000000, 7, 12.50, 0.25, true, '2024-03-10', '2024-03-10 08:30:00',
                        '2024-03-10 10:00:00+02', '08:30:15', 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
                        '{"a": {"b": [1, 2]}, "c": null}', '{x,y}', '{1,2}', NULL, '\\x4142');
                    """);

            Action rows = connector.actions().stream().filter(action -> action.name().equals("rows"))
                    .findFirst().orElseThrow();
            List<?> records = rows.handler().execute(new Arguments(Map.of("source", source, "table", "typed")));

            assertThat(records).hasSize(1);
            Map<?, ?> record = (Map<?, ?>) records.getFirst();
            assertThat(record.get("id")).isEqualTo(9000000000L);
            assertThat(record.get("small")).isEqualTo(7);
            assertThat(record.get("price")).isInstanceOfSatisfying(Number.class,
                    price -> assertThat(new java.math.BigDecimal(price.toString())).isEqualByComparingTo("12.50"));
            assertThat(record.get("ratio")).isEqualTo(0.25);
            assertThat(record.get("active")).isEqualTo(true);
            assertThat(record.get("born")).isEqualTo("2024-03-10");
            assertThat(record.get("seen")).isEqualTo("2024-03-10T08:30");
            assertThat(record.get("placed")).isEqualTo("2024-03-10T08:00:00Z");
            assertThat(record.get("at")).isEqualTo("08:30:15");
            assertThat(record.get("id_text")).isEqualTo("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11");
            Map<String, Object> doc = new java.util.LinkedHashMap<>();
            doc.put("a", Map.of("b", List.of(1, 2)));
            doc.put("c", null);
            assertThat(record.get("doc")).isEqualTo(doc);
            assertThat(record.get("tags")).isEqualTo(List.of("x", "y"));
            assertThat(record.get("numbers")).isEqualTo(List.of(1, 2));
            assertThat(record.containsKey("note")).isTrue();
            assertThat(record.get("note")).isNull();
            assertThat(record.get("raw")).isEqualTo("QUI=");
        }
    }

    private ExecutionResult run() {
        return Engine.run(new ExecutionRequest(workspace, "local", Map.of(), Selection.ALL, containers));
    }

    private void feature(String content) throws IOException {
        Path file = workspace.resolve("features/test.feature");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path path : paths.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
            }
        }
    }
}
