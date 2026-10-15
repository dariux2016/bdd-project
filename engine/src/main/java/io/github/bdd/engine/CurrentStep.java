package io.github.bdd.engine;

import io.cucumber.core.backend.HookDefinition;
import io.cucumber.core.backend.TestCaseState;
import io.cucumber.plugin.EventListener;
import io.cucumber.plugin.event.DataTableArgument;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.TestStepFinished;
import io.cucumber.plugin.event.TestStepStarted;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/** Knows the step being executed and where it is in its feature, which Cucumber does not tell a step definition. */
final class CurrentStep implements EventListener {

    private final URI workspace;
    /** {@code null} between a step and the next. */
    private PickleStepTestStep step;
    /** Where the rows of the table a step of a composite phrase receives are; {@code null} in the feature. */
    private IntFunction<String> tableRows;
    /** The steps the composite phrase of the current step ran; empty for any other phrase. */
    private List<StepResult> innerSteps = List.of();
    /** What Cucumber shows in its reports of the current scenario; {@code null} until a hook gives it. */
    private TestCaseState scenario;

    CurrentStep(Path workspace) {
        this.workspace = workspace.toUri();
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestStepStarted.class, event -> {
            if (event.getTestStep() instanceof PickleStepTestStep started) {
                step = started;
                innerSteps = List.of();
            }
        });
        publisher.registerHandlerFor(TestStepFinished.class, event -> step = null);
    }

    /** Whether a step is being executed that has neither a table nor a doc string. */
    boolean lacksArgument() {
        return step != null && step.getStep().getArgument() == null;
    }

    /**
     * @param index the position of a row in the table of the step, starting from zero
     * @return the feature and line of the row, e.g. {@code features/store.feature:12}, or where the
     *     catalog writes it for a table that a composite phrase gives one of its steps
     */
    String tableRow(int index) {
        if (tableRows != null) {
            return tableRows.apply(index);
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

    /**
     * Runs a step of a composite phrase whose table, if it has one, is written in the catalog.
     *
     * @param rows where the rows of that table are; {@code null} if the step has the table its
     *     composite phrase received, which is wherever that one was
     */
    <T> T withTableAt(IntFunction<String> rows, Supplier<T> innerStep) {
        IntFunction<String> previous = tableRows;
        if (rows != null) {
            tableRows = rows;
        }
        try {
            return innerStep.get();
        } finally {
            tableRows = previous;
        }
    }

    /** Records the steps the composite phrase of the current step ran, and shows them in the reports. */
    void ranInnerSteps(List<StepResult> steps, String detail) {
        innerSteps = List.copyOf(steps);
        if (scenario != null) {
            scenario.log(detail);
        }
    }

    /** The steps the composite phrase of the current step ran; empty for any other phrase. */
    List<StepResult> innerSteps() {
        return innerSteps;
    }

    /**
     * The hook that gives this the scenario being executed, so that the detail of a composite
     * phrase reaches the reports Cucumber writes. Cucumber only hands its scenario to hooks.
     */
    HookDefinition scenarioHook() {
        return new HookDefinition() {
            @Override
            public void execute(TestCaseState state) {
                scenario = state;
            }

            @Override
            public String getTagExpression() {
                return "";
            }

            @Override
            public int getOrder() {
                return 0;
            }

            @Override
            public boolean isDefinedAt(StackTraceElement stackTraceElement) {
                return false;
            }

            @Override
            public String getLocation() {
                return "engine";
            }
        };
    }
}
