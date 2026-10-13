package io.github.bdd.engine;

import io.cucumber.plugin.EventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestCaseStarted;
import io.cucumber.plugin.event.TestRunFinished;
import io.cucumber.plugin.event.TestRunStarted;
import io.cucumber.plugin.event.TestStepFinished;
import io.cucumber.plugin.event.TestStepStarted;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the execution result from the events Cucumber emits while running, telling the listener
 * about them as they come.
 */
final class ResultCollector implements EventListener {

    private final URI workspace;
    private final ExecutionListener listener;
    private final List<ScenarioResult> scenarios = new ArrayList<>();
    private List<StepResult> steps;
    /** The feature whose scenarios are running; {@code null} before the first and after the last. */
    private String feature;
    private Status featureStatus;

    ResultCollector(Path workspace, ExecutionListener listener) {
        this.workspace = workspace.toUri();
        this.listener = listener;
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestRunStarted.class, event -> listener.started());
        publisher.registerHandlerFor(TestCaseStarted.class, this::scenarioStarted);
        publisher.registerHandlerFor(TestStepStarted.class, this::stepStarted);
        publisher.registerHandlerFor(TestStepFinished.class, this::stepFinished);
        publisher.registerHandlerFor(TestCaseFinished.class, this::scenarioFinished);
        publisher.registerHandlerFor(TestRunFinished.class, event -> featureFinished());
    }

    private void scenarioStarted(TestCaseStarted event) {
        steps = new ArrayList<>();
        TestCase testCase = event.getTestCase();
        String uri = workspace.relativize(testCase.getUri()).getPath();
        if (!uri.equals(feature)) {
            featureFinished();
            feature = uri;
            featureStatus = Status.PASSED;
            listener.featureStarted(uri);
        }
        listener.scenarioStarted(uri, testCase.getLocation().getLine(), testCase.getName());
    }

    private void stepStarted(TestStepStarted event) {
        if (event.getTestStep() instanceof PickleStepTestStep testStep) {
            listener.stepStarted(testStep.getStep().getKeyword().strip(), testStep.getStep().getText());
        }
    }

    private void stepFinished(TestStepFinished event) {
        if (event.getTestStep() instanceof PickleStepTestStep testStep) {
            Status status = status(event.getResult());
            StepResult step = new StepResult(
                    testStep.getStep().getKeyword().strip(),
                    testStep.getStep().getText(),
                    status,
                    status == Status.FAILED ? message(event.getResult().getError()) : null);
            steps.add(step);
            listener.stepFinished(step);
        }
    }

    private void scenarioFinished(TestCaseFinished event) {
        TestCase testCase = event.getTestCase();
        ScenarioResult scenario = new ScenarioResult(
                workspace.relativize(testCase.getUri()).getPath(),
                testCase.getLocation().getLine(),
                testCase.getName(),
                status(event.getResult()),
                steps);
        scenarios.add(scenario);
        if (scenario.status() != Status.PASSED) {
            featureStatus = Status.FAILED;
        }
        listener.scenarioFinished(scenario);
    }

    private void featureFinished() {
        if (feature != null) {
            String finished = feature;
            feature = null;
            listener.featureFinished(finished, featureStatus);
        }
    }

    private static Status status(Result result) {
        return switch (result.getStatus()) {
            case PASSED -> Status.PASSED;
            case SKIPPED, UNUSED -> Status.SKIPPED;
            case UNDEFINED -> Status.UNDEFINED;
            case FAILED, AMBIGUOUS, PENDING -> Status.FAILED;
        };
    }

    private static String message(Throwable error) {
        if (error == null) {
            return null;
        }
        return error.getMessage() != null ? error.getMessage() : error.toString();
    }

    ExecutionResult result() {
        return new ExecutionResult(scenarios);
    }
}
