package io.github.bdd.engine;

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
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

/** Knows the step being executed and where it is in its feature, which Cucumber does not tell a step definition. */
final class CurrentStep implements EventListener {

    private final Path root;
    private final URI workspace;
    /** {@code null} between a step and the next. */
    private PickleStepTestStep step;

    CurrentStep(Path workspace) {
        this.root = workspace;
        this.workspace = workspace.toUri();
    }

    /**
     * @param path a file of the workspace, as a step gives it: relative to the workspace
     * @return the content of the file, read as UTF-8
     * @throws IllegalArgumentException if the path leaves the workspace or names no file
     */
    String readFile(String path) {
        Path file = root.resolve(path).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("The file '" + path + "' is outside the workspace");
        }
        try {
            return Files.readString(file);
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException("The file '" + path + "' does not exist in the workspace");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestStepStarted.class, event -> {
            if (event.getTestStep() instanceof PickleStepTestStep started) {
                step = started;
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
