package io.github.bdd.connector.kafka;

import io.github.bdd.engine.Engine;
import io.github.bdd.engine.ExecutionRequest;
import io.github.bdd.engine.ExecutionResult;
import io.github.bdd.engine.ScenarioResult;
import io.github.bdd.engine.Status;
import io.github.bdd.engine.StepResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs features against a Kafka broker in a container, which is the only way to observe a real one. */
class KafkaConnectorTest {

    private static final String IMAGE = "apache/kafka-native:4.2.0";
    private static final String SOURCES = """
            profiles:
              local:
                sources:
                  events:
                    type: kafka
                    config:
                      image: %s
                  shared:
                    type: kafka
                    readOnly: true
                    config:
                      image: %s
            """.formatted(IMAGE, IMAGE);

    @TempDir
    Path workspace;

    @BeforeAll
    static void dockerIsAvailable() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    }

    @Test
    void exampleWorkspaceRunsAgainstAContainerizedBroker() throws Exception {
        Path example = Path.of(KafkaConnectorTest.class.getResource("/example-workspace").toURI());
        copy(example, workspace);

        ExecutionResult result = Engine.run(new ExecutionRequest(workspace, "local", Map.of()));

        assertThat(result.scenarios()).hasSize(6);
        assertThat(result.scenarios()).allSatisfy(scenario -> assertThat(scenario.status())
                .as(scenario.name() + ": " + failures(scenario)).isEqualTo(Status.PASSED));
    }

    @Test
    void onlyMessagesArrivedSinceTheScenarioStartedAreObserved() throws IOException {
        String topic = "history-" + UUID.randomUUID();
        // What some other producer left on the topic before anything ran.
        publishDirectly(topic, "{\"id\": \"old\"}");

        ExecutionResult result = run("""
                Feature: History
                  Scenario: First
                    When the message '{"id": "first"}' is published to the topic "%1$s" of the Kafka source "events"
                    Then exactly 1 message exists on the topic "%1$s" of the Kafka source "events"
                    And no message exists on the topic "%1$s" of the Kafka source "events" for 1 second
                      | field | operator | value |
                      | id    | equals   | old   |

                  Scenario: Second
                    Then no message exists on the topic "%1$s" of the Kafka source "events" for 1 second
                    When the message '{"id": "second"}' is published to the topic "%1$s" of the Kafka source "events"
                    And I wait 1 second
                    Then exactly 1 message exists on the topic "%1$s" of the Kafka source "events"
                      | field | operator | value  |
                      | id    | equals   | second |
                    And a message on the topic "%1$s" of the Kafka source "events" equals the JSON
                      \"""
                      {"id": "second"}
                      \"""
                """.formatted(topic));

        assertPassed(result);
    }

    @Test
    void payloadThatIsNotJsonIsObservedAsText() throws IOException {
        String topic = "text-" + UUID.randomUUID();

        assertPassed(run("""
                Feature: Text
                  Scenario: Plain string
                    When the message 'hello world' is published to the topic "%1$s" of the Kafka source "events"
                    Then a message exists on the topic "%1$s" of the Kafka source "events"
                      | field | operator | value       |
                      | $     | equals   | hello world |
                """.formatted(topic)));
    }

    @Test
    void keyHeadersAndPartitionFailWithTheObservedRecord() throws IOException {
        String topic = "failing-" + UUID.randomUUID();
        write("settings.yaml", "verification:\n  timeout: 1s\n");

        ExecutionResult result = run("""
                Feature: Failing
                  Scenario: Wrong key
                    When the message '{"id": 1}' with the key "a" and the headers '{"trace": "t1"}' is published to the topic "%1$s" of the Kafka source "events"
                    Then a Kafka record exists on the topic "%1$s" of the Kafka source "events"
                      | field | operator | value |
                      | key   | equals   | b     |
                """.formatted(topic));

        StepResult step = result.scenarios().getFirst().steps().getLast();
        assertThat(step.status()).isEqualTo(Status.FAILED);
        assertThat(step.error())
                .startsWith("Expected a matching record, but 0 of the 1 observed records match")
                .contains("\"key\":\"a\"", "\"headers\":{\"trace\":\"t1\"}", "\"value\":{\"id\":1}");
    }

    @Test
    void orderThatDoesNotHoldFailsAndNamesWhatWasObserved() throws IOException {
        String topic = "order-" + UUID.randomUUID();
        write("settings.yaml", "verification:\n  timeout: 1s\n");

        ExecutionResult result = run("""
                Feature: Order
                  Scenario: Reversed
                    When the message '{"seq": 2}' is published to the topic "%1$s" of the Kafka source "events"
                    And the message '{"seq": 1}' is published to the topic "%1$s" of the Kafka source "events"
                    Then the messages on the topic "%1$s" of the Kafka source "events" arrive in the order of "seq": "1, 2"
                """.formatted(topic));

        StepResult step = result.scenarios().getFirst().steps().getLast();
        assertThat(step.status()).isEqualTo(Status.FAILED);
        assertThat(step.error()).contains("'2' was not observed after '1'")
                .contains("Observed seq in arrival order: [2, 1]");
    }

    @Test
    void messagesThatAppearAfterTheStepAreAwaited() throws IOException {
        String topic = "later-" + UUID.randomUUID();
        Thread late = new Thread(() -> {
            try {
                Thread.sleep(1500);
                publishDirectly(topic, "{\"id\": \"late\"}");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        late.start();

        ExecutionResult result = run("""
                Feature: Later
                  Scenario: Asynchronous system
                    Then a message exists on the topic "%1$s" of the Kafka source "events" within 15 seconds
                      | field | operator | value |
                      | id    | equals   | late  |
                """.formatted(topic));

        assertPassed(result);
    }

    @Test
    void readOnlySourceRefusesToPublish() throws IOException {
        String topic = "readonly-" + UUID.randomUUID();

        ExecutionResult result = run("""
                Feature: Read only
                  Scenario: Publishing
                    When the message '{"id": 1}' is published to the topic "%1$s" of the Kafka source "shared"
                    Then no message exists on the topic "%1$s" of the Kafka source "shared" for 1 second
                """.formatted(topic));

        StepResult publish = result.scenarios().getFirst().steps().getFirst();
        assertThat(publish.status()).isEqualTo(Status.FAILED);
        assertThat(publish.error()).contains("read-only");
        assertThat(result.scenarios().getFirst().steps().getLast().status()).isEqualTo(Status.SKIPPED);
    }

    @Test
    void sourceWithoutBrokerIsReportedWhenAStepUsesIt() throws IOException {
        write("sources.yaml", """
                profiles:
                  local:
                    sources:
                      events:
                        type: kafka
                """);

        ExecutionResult result = run("""
                Feature: No broker
                  Scenario: Publishing
                    When the message 'x' is published to the topic "t" of the Kafka source "events"
                """);

        assertThat(result.scenarios().getFirst().steps().getFirst().error())
                .contains("sets neither bootstrapServers nor image");
    }

    @Test
    void headersAreNameValuePairsOrAJsonObject() {
        assertThat(Messages.headers("a=1, b = two")).containsExactly(
                Map.entry("a", "1"), Map.entry("b", "two"));
        assertThat(Messages.headers("{\"a\": 1, \"b\": \"two\"}")).containsExactly(
                Map.entry("a", "1"), Map.entry("b", "two"));
    }

    private ExecutionResult run(String feature) throws IOException {
        Files.createDirectories(workspace.resolve("features"));
        Files.writeString(workspace.resolve("features/test.feature"), feature);
        if (!Files.exists(workspace.resolve("sources.yaml"))) {
            write("sources.yaml", SOURCES);
        }
        return Engine.run(new ExecutionRequest(workspace, "local", Map.of()));
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(workspace.resolve(name), content);
    }

    private static void assertPassed(ExecutionResult result) {
        assertThat(result.scenarios()).isNotEmpty();
        assertThat(result.scenarios()).allSatisfy(scenario -> assertThat(scenario.status())
                .as(scenario.name() + ": " + failures(scenario)).isEqualTo(Status.PASSED));
    }

    private static List<String> failures(ScenarioResult scenario) {
        return scenario.steps().stream()
                .filter(step -> step.error() != null)
                .map(step -> step.text() + " -> " + step.error())
                .toList();
    }

    private static void publishDirectly(String topic, String payload) {
        try {
            String servers = Brokers.bootstrapServers(new io.github.bdd.plugin.Source("direct",
                    Map.of(Brokers.IMAGE, IMAGE)));
            new Messages().publish(servers, topic, null, Map.of(), payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
