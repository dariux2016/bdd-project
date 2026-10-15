package io.github.bdd.plugin;

/**
 * A sentence usable as a Gherkin step, bound to an action.
 *
 * <p>Each language of the catalog has its own phrase for an action, so that a feature written with
 * the Gherkin keywords of a language reads naturally; all of them lead to the same action.
 *
 * @param pattern a Cucumber Expression in which each parameter is written as the name of an action
 *     parameter between braces, e.g. <code>the store has {key} set to {value}</code>; it must use
 *     every parameter of the action exactly once. A phrase with a verification may also use the
 *     parameters of that {@link Verification} and <code>{timeout}</code>, which no action
 *     parameter may be named after
 * @param action the name of the target action in the declaring connector
 * @param verification what the engine checks on the records the action returns, which must be a
 *     {@link ActionKind#READ} one; {@code null} for a phrase that only performs the action
 * @param language the language of the pattern, as a lowercase ISO 639-1 code: {@code en} or
 *     {@code it}
 * @param id what identifies the phrase whatever its wording, shared by its versions in each
 *     language; {@code null} if it has none. A workspace may use a phrase before any connector
 *     implements it, declaring it pending with an id: once a connector declares a phrase with that
 *     id, the pending phrase leads to its action
 */
public record Phrase(String pattern, String action, Verification verification, String language, String id) {

    public static final String ENGLISH = "en";
    public static final String ITALIAN = "it";

    public Phrase {
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("The phrase '" + pattern + "' has no language");
        }
        if (id != null && id.isBlank()) {
            throw new IllegalArgumentException("The phrase '" + pattern + "' has a blank id");
        }
    }

    /** A phrase without an id. */
    public Phrase(String pattern, String action, Verification verification, String language) {
        this(pattern, action, verification, language, null);
    }

    /** An English phrase. */
    public Phrase(String pattern, String action, Verification verification) {
        this(pattern, action, verification, ENGLISH);
    }

    /** An English phrase that only performs the action. */
    public Phrase(String pattern, String action) {
        this(pattern, action, null, ENGLISH);
    }

    /** The same phrase, worded in another language. */
    public Phrase in(String language, String pattern) {
        return new Phrase(pattern, action, verification, language, id);
    }

    /** The same phrase, with an id. */
    public Phrase withId(String id) {
        return new Phrase(pattern, action, verification, language, id);
    }
}
