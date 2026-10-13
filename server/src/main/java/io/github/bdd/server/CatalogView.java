package io.github.bdd.server;

import io.github.bdd.engine.CatalogEntry;
import io.github.bdd.plugin.Parameter;
import io.github.bdd.plugin.ParameterType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The catalog as the UI reads it: filtered, and with a template of each phrase ready to be written in a step. */
final class CatalogView {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
    private static final Pattern OPTIONAL = Pattern.compile("\\(([^()]*)\\)");
    private static final Pattern ALTERNATION = Pattern.compile("[^\\s/{}()]+(?:/[^\\s/{}()]+)+");

    private CatalogView() {
    }

    /**
     * @param source the source type the phrases act on ({@code engine} for those acting on none);
     *     {@code null} for any
     * @param language {@code en} or {@code it}; {@code null} for any
     * @param text part of the pattern, in any case; {@code null} for any
     */
    static List<Map<String, Object>> phrases(List<CatalogEntry> catalog, String source, String language,
            String text) {
        String needle = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> phrases = new ArrayList<>();
        for (CatalogEntry entry : catalog) {
            String phraseSource = sourceOf(entry);
            if (source != null && !source.isBlank() && !source.equals(phraseSource)) {
                continue;
            }
            if (language != null && !language.isBlank() && !language.equals(entry.language())) {
                continue;
            }
            if (!needle.isEmpty() && !entry.pattern().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            phrases.add(describe(entry, phraseSource));
        }
        return phrases;
    }

    private static String sourceOf(CatalogEntry entry) {
        int dot = entry.target().indexOf('.');
        return dot < 0 ? entry.target() : entry.target().substring(0, dot);
    }

    private static Map<String, Object> describe(CatalogEntry entry, String source) {
        Map<String, Object> phrase = new LinkedHashMap<>();
        phrase.put("pattern", entry.pattern());
        phrase.put("language", entry.language());
        phrase.put("origin", entry.origin());
        phrase.put("source", source);
        phrase.put("target", entry.target());
        phrase.put("verification", entry.verification() == null ? null : entry.verification().name());
        List<Map<String, String>> parameters = new ArrayList<>();
        for (Parameter parameter : entry.parameters()) {
            parameters.add(Map.of("name", parameter.name(), "type", parameter.type().name()));
        }
        phrase.put("parameters", parameters);
        phrase.put("template", template(entry));
        return phrase;
    }

    /**
     * The phrase written as a step: strings between quotes named after their parameter, so that the
     * author replaces them; numbers as {@code 0}; the first of the alternative words and the optional
     * text included.
     */
    static String template(CatalogEntry entry) {
        Map<String, ParameterType> types = new LinkedHashMap<>();
        entry.parameters().forEach(parameter -> types.put(parameter.name(), parameter.type()));
        String text = PLACEHOLDER.matcher(entry.pattern()).replaceAll(match -> {
            ParameterType type = types.get(match.group(1));
            return Matcher.quoteReplacement(type == ParameterType.INTEGER ? "0" : "\"" + match.group(1) + "\"");
        });
        text = OPTIONAL.matcher(text).replaceAll(match -> Matcher.quoteReplacement(match.group(1)));
        return ALTERNATION.matcher(text).replaceAll(match -> {
            String first = match.group().split("/")[0];
            return Matcher.quoteReplacement(first);
        });
    }
}
