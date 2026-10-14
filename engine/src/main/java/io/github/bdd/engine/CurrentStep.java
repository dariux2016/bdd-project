package io.github.bdd.engine;

import io.cucumber.plugin.EventListener;
import io.cucumber.plugin.event.DataTableArgument;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.TestCaseStarted;
import io.cucumber.plugin.event.TestStepFinished;
import io.cucumber.plugin.event.TestStepStarted;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Knows the step being executed and where it is in its feature, which Cucumber does not tell a step definition. */
final class CurrentStep implements EventListener {

    private final Path workspacePath;
    private final URI workspace;
    /** {@code null} between a step and the next. */
    private PickleStepTestStep step;
    private Instant scenarioStart = Instant.EPOCH;
    /** Where the inner step of a composite phrase being executed is written; {@code null} if none is. */
    private String innerLocation;
    private List<StepResult> inner = List.of();

    private final Cancellation cancellation;

    CurrentStep(Path workspace, Cancellation cancellation) {
        this.workspacePath = workspace;
        this.workspace = workspace.toUri();
        this.cancellation = cancellation;
    }

    /** @throws IllegalStateException if the execution was cancelled */
    void beginExecution() {
        cancellation.enterStep();
    }

    void endExecution() {
        cancellation.leaveStep();
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestCaseStarted.class, event -> scenarioStart = event.getInstant());
        publisher.registerHandlerFor(TestStepStarted.class, event -> {
            if (event.getTestStep() instanceof PickleStepTestStep started) {
                step = started;
                inner = List.of();
            }
        });
        publisher.registerHandlerFor(TestStepFinished.class, event -> step = null);
    }

    Path workspace() {
        return workspacePath;
    }

    /** When the scenario being executed started. */
    Instant scenarioStart() {
        return scenarioStart;
    }

    /** Whether a step is being executed that has neither a table nor a doc string. */
    boolean lacksArgument() {
        return step != null && step.getStep().getArgument() == null;
    }

    /**
     * Declares that the step being executed is an inner step of a composite phrase, written at
     * {@code location}, so that what is reported about its table cites that instead of the feature.
     *
     * @return what to give back to {@link #leaveInner} once the inner step is done
     */
    String enterInner(String location) {
        String outer = innerLocation;
        innerLocation = location;
        return outer;
    }

    void leaveInner(String outer) {
        innerLocation = outer;
    }

    /** Records the inner steps of the composite phrase that was just executed, to report with its step. */
    void expanded(List<StepResult> steps) {
        inner = List.copyOf(steps);
    }

    /** The inner steps of the step being executed, or of the one that finished last. */
    List<StepResult> inner() {
        return inner;
    }

    /**
     * @param index the position of a row in the table of the step, starting from zero
     * @return the feature and line of the row, e.g. {@code features/store.feature:12}
     */
    String tableRow(int index) {
        if (innerLocation != null) {
            return innerLocation + ", row " + (index + 1);
        }
        int line = ((DataTableArgument) step.getStep().getArgument()).getLine();
        List<String> lines;
        try {
            lines = Files.readAllLines(Path.of(step.getUri()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Comments and blank lines may sit between the rows of a table.
        for (int row = -1; ; line++) {
            if (lines.get(line - 1).strip().startsWith("|") && ++row == index) {
                return workspace.relativize(step.getUri()).getPath() + ":" + line;
            }
        }
    }
}
