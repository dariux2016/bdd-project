package io.github.bdd.engine;

import io.cucumber.plugin.EventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestCaseStarted;
import io.cucumber.plugin.event.TestStepFinished;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Builds the execution result from the events Cucumber emits while running. */
final class ResultCollector implements EventListener {

    private final URI workspace;
    private final CurrentStep currentStep;
    private final List<ScenarioResult> scenarios = new ArrayList<>();
    private List<StepResult> steps;

    ResultCollector(Path workspace, CurrentStep currentStep) {
        this.workspace = workspace.toUri();
        this.currentStep = currentStep;
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestCaseStarted.class, event -> steps = new ArrayList<>());
        publisher.registerHandlerFor(TestStepFinished.class, this::stepFinished);
        publisher.registerHandlerFor(TestCaseFinished.class, this::scenarioFinished);
    }

    private void stepFinished(TestStepFinished event) {
        if (event.getTestStep() instanceof PickleStepTestStep testStep) {
            Status status = status(event.getResult());
            steps.add(new StepResult(
                    testStep.getStep().getKeyword().strip(),
                    testStep.getStep().getText(),
                    status,
                    status == Status.FAILED ? message(event.getResult().getError()) : null,
                    currentStep.innerSteps()));
        }
    }

    private void scenarioFinished(TestCaseFinished event) {
        TestCase testCase = event.getTestCase();
        scenarios.add(new ScenarioResult(
                workspace.relativize(testCase.getUri()).getPath(),
                testCase.getLocation().getLine(),
                testCase.getName(),
                status(event.getResult()),
                steps));
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
