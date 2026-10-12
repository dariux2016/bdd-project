package io.github.bdd.engine;

/**
 * @param keyword the Gherkin keyword as written in the feature, without trailing space
 * @param error why the step failed; {@code null} unless the status is {@link Status#FAILED}
 */
public record StepResult(String keyword, String text, Status status, String error) {
}
