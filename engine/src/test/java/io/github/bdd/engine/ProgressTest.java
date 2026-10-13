package io.github.bdd.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressTest {

    @TempDir
    Path workspace;

    @BeforeEach
    void declareTheFruitSource() throws IOException {
        Files.writeString(workspace.resolve("sources.yaml"), """
                profiles:
                  local:
                    sources:
                      fruit:
                        type: fake
                """);
    }

    private void feature(String name, String content) throws IOException {
        Path file = workspace.resolve("features").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** Records every event of an execution as a short line. */
    private static final class Recorder implements ExecutionListener {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void started() {
            events.add("started");
        }

        @Override
        public void featureStarted(String feature) {
            events.add("feature+ " + feature);
        }

        @Override
        public void scenarioStarted(String feature, int line, String name) {
            events.add("scenario+ " + name + "@" + line);
        }

        @Override
        public void stepStarted(String keyword, String text) {
            events.add("step+ " + keyword + " " + text);
        }

        @Override
        public void stepFinished(StepResult step) {
            events.add("step- " + step.status() + (step.error() == null ? "" : " " + step.error()));
        }

        @Override
        public void scenarioFinished(ScenarioResult scenario) {
            events.add("scenario- " + scenario.name() + " " + scenario.status());
        }

        @Override
        public void featureFinished(String feature, Status status) {
            events.add("feature- " + feature + " " + status);
        }
    }

    @Test
    void listenerSeesTheStartAndEndOfFeaturesScenariosAndStepsInOrder() throws IOException {
        feature("a.feature", """
                Feature: A
                  Scenario: Passing
                    Given the fake store "fruit" has "apples" set to 5
                    Then the fake store "fruit" should have "apples" equal to 6
                    And the fake store "fruit" should have "apples" equal to 5
                """);
        feature("b.feature", """
                Feature: B
                  Scenario: Fine
                    Given the fake store "fruit" has "pears" set to 1
                """);
        Recorder recorder = new Recorder();

        Engine.run(new ExecutionRequest(workspace, "local", Map.of()), recorder, new Cancellation());

        assertThat(recorder.events).containsExactly(
                "started",
                "feature+ features/a.feature",
                "scenario+ Passing@2",
                "step+ Given the fake store \"fruit\" has \"apples\" set to 5",
                "step- PASSED",
                "step+ Then the fake store \"fruit\" should have \"apples\" equal to 6",
                "step- FAILED Expected \"apples\" to equal 6 but was 5",
                "step+ And the fake store \"fruit\" should have \"apples\" equal to 5",
                "step- SKIPPED",
                "scenario- Passing FAILED",
                "feature- features/a.feature FAILED",
                "feature+ features/b.feature",
                "scenario+ Fine@2",
                "step+ Given the fake store \"fruit\" has \"pears\" set to 1",
                "step- PASSED",
                "scenario- Fine PASSED",
                "feature- features/b.feature PASSED");
    }

    @Test
    void cancellingStopsAnExecutionWaitingForData() throws Exception {
        feature("slow.feature", """
                Feature: Slow
                  Scenario: Waits for data that never comes
                    Then a record exists in the fake store "fruit" within 30 seconds
                  Scenario: Never reached
                    Given the fake store "fruit" has "apples" set to 5
                """);
        Cancellation cancellation = new Cancellation();
        ExecutionListener cancelling = new ExecutionListener() {
            @Override
            public void stepStarted(String keyword, String text) {
                Thread.ofPlatform().start(() -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        return;
                    }
                    cancellation.cancel();
                });
            }
        };
        long start = System.nanoTime();

        ExecutionResult result = Engine.run(new ExecutionRequest(workspace, "local", Map.of()),
                cancelling, cancellation);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        assertThat(cancellation.isCancelled()).isTrue();
        assertThat(result.successful()).isFalse();
        ScenarioResult waiting = result.scenarios().getFirst();
        assertThat(waiting.steps().getFirst().status()).isEqualTo(Status.FAILED);
        assertThat(result.scenarios()).allSatisfy(
                scenario -> assertThat(scenario.status()).isNotEqualTo(Status.PASSED));
    }

    @Test
    void cancelledExecutionStillWritesItsReports() throws Exception {
        feature("one.feature", """
                Feature: One
                  Scenario: Only
                    Given the fake store "fruit" has "apples" set to 5
                """);
        Cancellation cancellation = new Cancellation();
        cancellation.cancel();

        ExecutionResult result = Engine.run(new ExecutionRequest(workspace, "local", Map.of()),
                new ExecutionListener() { }, cancellation);

        assertThat(result.reports()).isDirectory();
        assertThat(result.successful()).isFalse();
    }
}
