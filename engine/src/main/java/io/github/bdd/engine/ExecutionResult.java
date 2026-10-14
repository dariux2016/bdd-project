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

    /**
     * Whether the selection matched at least one scenario and none failed. A scenario that is only
     * {@code pending} does not count as failed: see {@link #successful(boolean)}.
     */
    public boolean successful() {
        return successful(false);
    }

    /**
     * @param failOnPending whether a pending scenario makes the execution unsuccessful
     */
    public boolean successful(boolean failOnPending) {
        return !noScenarioSelected() && scenarios.stream().allMatch(scenario ->
                scenario.status() == Status.PASSED || (!failOnPending && scenario.status() == Status.PENDING));
    }

    /** Whether at least one scenario is pending: it stopped at a phrase with no implementation yet. */
    public boolean hasPending() {
        return scenarios.stream().anyMatch(scenario -> scenario.status() == Status.PENDING);
    }

    /** Whether the selection matched no scenario at all, so that nothing was verified. */
    public boolean noScenarioSelected() {
        return scenarios.isEmpty();
    }
}
