package io.github.bdd.plugin;

/**
 * A sentence usable as a Gherkin step, bound to an action.
 *
 * @param pattern a Cucumber Expression in which each parameter is written as the name of an action
 *     parameter between braces, e.g. <code>the store has {key} set to {value}</code>; it must use
 *     every parameter of the action exactly once
 * @param action the name of the target action in the declaring connector
 */
public record Phrase(String pattern, String action) {
}
