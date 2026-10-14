package io.github.bdd.engine;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * @param keyword the Gherkin keyword as written in the feature, without trailing space
 * @param error why the step failed; {@code null} unless the status is {@link Status#FAILED}
 * @param inner the steps a composite phrase expanded into, in order, each with its own outcome and
 *     its own inner steps if it is a composite too; empty for any other step
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record StepResult(String keyword, String text, Status status, String error, List<StepResult> inner) {

    public StepResult {
        inner = List.copyOf(inner);
    }

    public StepResult(String keyword, String text, Status status, String error) {
        this(keyword, text, status, error, List.of());
    }
}
