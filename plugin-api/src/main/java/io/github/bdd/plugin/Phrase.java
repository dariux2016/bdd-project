package io.github.bdd.plugin;

/**
 * A sentence usable as a Gherkin step, bound to an action.
 *
 * @param pattern a Cucumber Expression in which each parameter is written as the name of an action
 *     parameter between braces, e.g. <code>the store has {key} set to {value}</code>; it must use
 *     every parameter of the action exactly once. A phrase with a verification may also use the
 *     parameters of that {@link Verification}, which no action parameter may be named after
 * @param action the name of the target action in the declaring connector
 * @param verification what the engine checks on the records the action returns; {@code null} for a
 *     phrase that only performs the action
 */
public record Phrase(String pattern, String action, Verification verification) {

    /** A phrase that only performs the action. */
    public Phrase(String pattern, String action) {
        this(pattern, action, null);
    }
}
