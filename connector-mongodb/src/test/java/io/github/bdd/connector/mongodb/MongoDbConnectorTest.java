package io.github.bdd.connector.mongodb;

import io.github.bdd.engine.Engine;
import io.github.bdd.engine.ExecutionRequest;
import io.github.bdd.engine.ExecutionResult;
import io.github.bdd.engine.ScenarioResult;
import io.github.bdd.engine.Selection;
import io.github.bdd.engine.Status;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Executes the example workspace, which is also the documentation of the connector, on a real MongoDB. */
class MongoDbConnectorTest {

    private static final Path EXAMPLE = Path.of("..", "examples", "mongodb");

    @TempDir
    Path workspace;

    @BeforeAll
    static void needDocker() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    }

    @Test
    void exampleFeaturesPassOnAContainerStartedFromTheImage() throws IOException {
        copyExample();

        ExecutionResult result = run("local", Map.of());

        assertThat(result.scenarios()).extracting(ScenarioResult::name).contains(
                "Documents inserted from text are checked with the unified table",
                "Documents inserted from a file",
                "A native filter checks and captures a value",
                "ObjectIds, dates and decimals have a stable JSON form",
                "Gli stessi controlli in italiano");
        assertThat(result.scenarios()).allSatisfy(scenario -> assertThat(scenario.status())
                .describedAs(scenario.name()).isEqualTo(Status.PASSED));
    }

    @Test
    void failedVerificationShowsTheObservedDocumentsInJson() throws IOException {
        copyExample();
        feature("failing.feature", """
                Feature: Failing
                  Scenario: Wrong status
                    Given the MongoDB collection "orders" of source "orders-db" is emptied
                    And the documents below are inserted in the MongoDB collection "orders" of source "orders-db"
                      \"\"\"
                      {"_id": {"$oid": "65f0c0ffee0000000000abcd"}, "status": "NEW"}
                      \"\"\"
                    Then a document exists in the MongoDB collection "orders" of source "orders-db" within 1 second
                      | field  | operator | value |
                      | status | equals   | PAID  |
                """);

        ScenarioResult scenario = run("local", Map.of(), "features/failing.feature").scenarios().getFirst();

        assertThat(scenario.status()).isEqualTo(Status.FAILED);
        assertThat(scenario.steps().getLast().error()).contains("65f0c0ffee0000000000abcd").contains("NEW");
    }

    @Test
    void writesAreRefusedOnAReadOnlySourceAndReadsStillWork() throws IOException {
        copyExample();
        Files.writeString(workspace.resolve("sources.yaml"), """
                profiles:
                  frozen:
                    sources:
                      orders-db:
                        type: mongodb
                        readOnly: true
                        config:
                          image: mongo:7
                          database: shop
                """);
        feature("frozen.feature", """
                Feature: Frozen
                  Scenario: Reading is allowed
                    Then no document exists in the MongoDB collection "frozen-orders" of source "orders-db" for 1 second
                  Scenario: Clearing is not
                    Given the MongoDB collection "frozen-orders" of source "orders-db" is emptied
                  Scenario: Inserting is not
                    Given the documents below are inserted in the MongoDB collection "frozen-orders" of source "orders-db"
                      \"\"\"
                      {"a": 1}
                      \"\"\"
                  Scenario: Inserting from a file is not
                    Given the documents of the file "data/orders.json" are inserted in the MongoDB collection "frozen-orders" of source "orders-db"
                """);

        ExecutionResult result = run("frozen", Map.of(), "features/frozen.feature");

        assertThat(result.scenarios()).extracting(ScenarioResult::status)
                .containsExactly(Status.PASSED, Status.FAILED, Status.FAILED, Status.FAILED);
        assertThat(result.scenarios().get(1).steps().getFirst().error()).contains("read-only");
    }

    @Test
    void sourceCanReachARunningMongoDbByUri() throws IOException {
        try (GenericContainer<?> running = new GenericContainer<>(DockerImageName.parse("mongo:7"))
                .withExposedPorts(27017)
                .waitingFor(Wait.forLogMessage(".*[Ww]aiting for connections.*", 1))) {
            running.start();
            copyExample();
            Files.writeString(workspace.resolve("sources.yaml"), """
                    profiles:
                      shared:
                        sources:
                          orders-db:
                            type: mongodb
                            config:
                              uri: ${MONGODB_URI}
                              database: shop
                    """);
            feature("uri.feature", """
                    Feature: By uri
                      Scenario: Round trip
                        Given the MongoDB collection "uri-orders" of source "orders-db" is emptied
                        And the documents below are inserted in the MongoDB collection "uri-orders" of source "orders-db"
                          \"\"\"
                          {"a": 1}
                          \"\"\"
                        Then exactly 1 document exists in the MongoDB collection "uri-orders" of source "orders-db"
                    """);

            ExecutionResult result = run("shared",
                    Map.of("MONGODB_URI", "mongodb://" + running.getHost() + ":" + running.getMappedPort(27017)),
                    "features/uri.feature");

            assertThat(result.successful()).isTrue();
        }
    }

    private ExecutionResult run(String profile, Map<String, String> environment, String... paths) {
        return Engine.run(new ExecutionRequest(workspace, profile, environment,
                new Selection(List.of(paths), null)));
    }

    private void feature(String name, String content) throws IOException {
        Files.writeString(workspace.resolve("features").resolve(name), content);
    }

    private void copyExample() throws IOException {
        try (Stream<Path> files = Files.walk(EXAMPLE)) {
            files.forEach(source -> {
                Path target = workspace.resolve(EXAMPLE.relativize(source).toString());
                try {
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(source, target);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
