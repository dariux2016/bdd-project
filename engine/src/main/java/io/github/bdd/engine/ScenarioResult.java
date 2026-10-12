package io.github.bdd.engine;

import java.util.List;

/**
 * @param feature path of the feature file relative to the workspace, with forward slashes
 * @param line line of the scenario in the feature file; for a Scenario Outline, the line of the
 *     example row
 */
public record ScenarioResult(String feature, int line, String name, Status status, List<StepResult> steps) {

    public ScenarioResult {
        steps = List.copyOf(steps);
    }
}
