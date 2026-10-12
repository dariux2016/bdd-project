package io.github.bdd.engine;

import java.util.List;

public record ExecutionResult(List<ScenarioResult> scenarios) {

    public ExecutionResult {
        scenarios = List.copyOf(scenarios);
    }

    /** Whether every scenario passed. */
    public boolean successful() {
        return scenarios.stream().allMatch(scenario -> scenario.status() == Status.PASSED);
    }
}
