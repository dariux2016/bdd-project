package io.github.bdd.engine;

import java.nio.file.Path;
import java.util.List;

/**
 * @param scenarios the scenarios that ran, in execution order
 * @param reports the folder with the reports of the execution; {@code null} if none was written
 */
public record ExecutionResult(List<ScenarioResult> scenarios, Path reports) {

    public ExecutionResult {
        scenarios = List.copyOf(scenarios);
    }

    public ExecutionResult(List<ScenarioResult> scenarios) {
        this(scenarios, null);
    }

    /** Whether the selection matched at least one scenario and every scenario passed. */
    public boolean successful() {
        return !noScenarioSelected()
                && scenarios.stream().allMatch(scenario -> scenario.status() == Status.PASSED);
    }

    /** Whether the selection matched no scenario at all, so that nothing was verified. */
    public boolean noScenarioSelected() {
        return scenarios.isEmpty();
    }
}
