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

    private final URI workspace;
    /** {@code null} between a step and the next. */
    private PickleStepTestStep step;
    private Instant scenarioStart = Instant.EPOCH;

    CurrentStep(Path workspace) {
        this.workspace = workspace.toUri();
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestCaseStarted.class, event -> scenarioStart = event.getInstant());
        publisher.registerHandlerFor(TestStepStarted.class, event -> {
            if (event.getTestStep() instanceof PickleStepTestStep started) {
                step = started;
            }
        });
        publisher.registerHandlerFor(TestStepFinished.class, event -> step = null);
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
     * @param index the position of a row in the table of the step, starting from zero
     * @return the feature and line of the row, e.g. {@code features/store.feature:12}
     */
    String tableRow(int index) {
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
