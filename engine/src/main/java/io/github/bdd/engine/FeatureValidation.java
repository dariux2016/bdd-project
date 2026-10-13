package io.github.bdd.engine;

import java.util.List;

/**
 * What the catalog says of the text of a feature, as it would at execution.
 *
 * @param language the Gherkin language of the feature: {@code en} unless it declares another
 * @param errors the syntax errors of the text; when there are any, no step is validated
 * @param steps the steps of the feature that have a scenario, in the order of the text
 */
public record FeatureValidation(String language, List<SyntaxError> errors, List<StepValidation> steps) {

    public FeatureValidation {
        errors = List.copyOf(errors);
        steps = List.copyOf(steps);
    }

    /** The step is matched by exactly one phrase and would run. */
    public static final String MATCHED = "matched";
    /** No phrase matches the step. */
    public static final String UNDEFINED = "undefined";
    /** More than one phrase matches the step, which Cucumber refuses to run. */
    public static final String AMBIGUOUS = "ambiguous";
    /** The phrase matching the step is not implemented yet: the step is reported as pending. */
    public static final String PENDING = "pending";

    /** @param line 1-based line of the text */
    public record SyntaxError(int line, String message) {
    }

    /**
     * @param line 1-based line of the step in the text
     * @param text the text of the step, after the keyword; for a Scenario Outline, as of its first example
     * @param status {@link #MATCHED}, {@link #UNDEFINED}, {@link #AMBIGUOUS} or {@link #PENDING}
     * @param phrase the pattern of the matching phrase; {@code null} unless the step is matched
     * @param origin where that phrase comes from, as in {@link CatalogEntry#origin()}
     * @param message why the step is not matched; {@code null} when it is
     */
    public record StepValidation(int line, String text, String status, String phrase, String origin,
            String message) {
    }
}
