package io.github.bdd.plugin;

/**
 * A check the engine performs on the records returned by the action of a {@link Phrase}.
 *
 * <p>The checks on the number of records count those satisfying the filter the step carries as a
 * table of <code>field | operator | value</code> rows; without a table, every record counts. The
 * checks against a JSON take the expected one from the doc string of the step.
 *
 * <p>The data of a source may appear some time after the action that causes it, so the engine
 * repeats the action and the check until the check is satisfied or a timeout expires. The checks
 * that later data can only break, {@link #NOT_EXISTS} and {@link #COUNT_AT_MOST}, are instead
 * repeated for the whole time and fail as soon as they are not satisfied. The timeout is that of the
 * workspace, unless the phrase uses <code>{timeout}</code>: a number of seconds.
 */
public enum Verification {
    /** At least one record satisfies the filter. */
    EXISTS,
    /** No record satisfies the filter. */
    NOT_EXISTS,
    /** As many records as the <code>{count}</code> of the phrase satisfy the filter. */
    COUNT_EXACTLY,
    /** No fewer records than the <code>{count}</code> of the phrase satisfy the filter. */
    COUNT_AT_LEAST,
    /** No more records than the <code>{count}</code> of the phrase satisfy the filter. */
    COUNT_AT_MOST,
    /**
     * A record has the same fields and values as the expected JSON. The phrase may use
     * <code>{ignored}</code>: a comma-separated list of JSONPaths left out of the comparison.
     */
    JSON_STRICT,
    /**
     * A record has every field of the expected JSON, with the same value; its other fields do not
     * matter. Arrays must still match element by element. The phrase may use <code>{ignored}</code>
     * as for {@link #JSON_STRICT}.
     */
    JSON_PARTIAL,
    /**
     * Saves a field of the first record that satisfies the filter in a variable of the scenario, for
     * the steps that follow. The phrase must use <code>{field}</code>, a JSONPath, and
     * <code>{variable}</code>, the name of the variable. It is repeated like the other checks until
     * a record has the field.
     */
    SAVE
}
