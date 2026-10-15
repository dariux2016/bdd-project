package io.github.bdd.engine;

import java.util.List;

/**
 * @param keyword the Gherkin keyword as written in the feature, without trailing space; {@code *}
 *     for a step of a composite phrase, which has none
 * @param error why the step failed; {@code null} unless the status is {@link Status#FAILED}
 * @param steps the steps a composite phrase ran, in order; empty for any other phrase
 */
public record StepResult(String keyword, String text, Status status, String error, List<StepResult> steps) {

    /** The keyword of the steps of a composite phrase. */
    public static final String INNER_KEYWORD = "*";

    public StepResult {
        steps = List.copyOf(steps);
    }

    /** The result of a step that is not a composite phrase. */
    public StepResult(String keyword, String text, Status status, String error) {
        this(keyword, text, status, error, List.of());
    }
}
