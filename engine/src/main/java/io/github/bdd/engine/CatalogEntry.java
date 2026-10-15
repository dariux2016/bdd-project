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
 * @param target the action the phrase leads to, as {@code <source type>.<action>}; {@code null} for
 *     a composite phrase, which leads to its steps instead, and for a pending one
 * @param id what identifies the phrase whatever its wording; {@code null} if it has none
 * @param verification what the engine checks on the records of the action; {@code null} if nothing
 * @param parameters the parameters the phrase takes, in the order the pattern uses them
 * @param steps the steps a composite phrase expands into, as the catalog writes them; empty for any
 *     other phrase
 * @param pending whether no installed connector implements the phrase yet, so that a step of it is
 *     pending
 */
public record CatalogEntry(String pattern, String language, String origin, String target, String id,
        Verification verification, List<Parameter> parameters, List<String> steps, boolean pending) {

    public CatalogEntry {
        parameters = List.copyOf(parameters);
        steps = List.copyOf(steps);
    }
}
