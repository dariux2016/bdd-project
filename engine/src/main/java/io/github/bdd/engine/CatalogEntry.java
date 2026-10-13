package io.github.bdd.engine;

import io.github.bdd.plugin.Parameter;
import io.github.bdd.plugin.Verification;

import java.util.List;

/**
 * A phrase of the effective catalog, as the engine answers whoever queries it.
 *
 * @param pattern the Cucumber Expression of the phrase
 * @param language the language of the pattern: {@code en} or {@code it}
 * @param origin where the phrase comes from: {@code connector:<type>}, {@code engine} for the phrases
 *     every catalog has, or {@code workspace} for those of the workspace's {@code catalog.yaml}
 * @param target the action the phrase leads to, as {@code <source type>.<action>}
 * @param verification what the engine checks on the records of the action; {@code null} if nothing
 * @param parameters the parameters the phrase takes, in the order the pattern uses them
 * @param steps for a composite phrase, the steps it expands into, as written in the catalog; empty
 *     for any other phrase
 */
public record CatalogEntry(String pattern, String language, String origin, String target,
        Verification verification, List<Parameter> parameters, List<String> steps) {

    public CatalogEntry {
        parameters = List.copyOf(parameters);
        steps = List.copyOf(steps);
    }

    public CatalogEntry(String pattern, String language, String origin, String target,
            Verification verification, List<Parameter> parameters) {
        this(pattern, language, origin, target, verification, parameters, List.of());
    }
}
