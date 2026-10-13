package io.github.bdd.engine;

import io.cucumber.core.feature.FeatureWithLines;
import io.cucumber.tagexpressions.Expression;
import io.cucumber.tagexpressions.TagExpressionParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What to execute of a workspace. Both parts narrow the selection: a scenario runs if it is under one
 * of the paths and its tags satisfy the expression.
 *
 * @param paths folders, files ({@code features/orders.feature}) or single scenarios
 *     ({@code features/orders.feature:12}, the line of the scenario or of any of its steps), relative
 *     to the workspace and inside {@code features/}; empty for every feature
 * @param tags a Cucumber tag expression such as {@code @smoke}, {@code not @wip} or
 *     {@code @a and (@b or @c)}; {@code null} for no filter on tags
 */
public record Selection(List<String> paths, String tags) {

    /** The whole workspace. */
    public static final Selection ALL = new Selection(List.of(), null);

    public Selection {
        paths = List.copyOf(paths);
        tags = tags == null || tags.isBlank() ? null : tags.strip();
    }

    private static final Pattern WITH_LINE = Pattern.compile("(.+):(\\d+)");

    /**
     * @throws IllegalArgumentException if a path does not exist or is outside {@code features/}, or if
     *     the tag expression is invalid
     */
    List<FeatureWithLines> features(Path workspace) {
        Path features = workspace.resolve("features");
        List<FeatureWithLines> selected = new ArrayList<>();
        if (paths.isEmpty()) {
            selected.add(FeatureWithLines.create(features.toUri(), List.of()));
        }
        for (String spec : paths) {
            Matcher withLine = WITH_LINE.matcher(spec);
            String name = withLine.matches() ? withLine.group(1) : spec;
            List<Integer> lines = withLine.matches() ? List.of(Integer.parseInt(withLine.group(2))) : List.of();
            Path path = workspace.resolve(name).normalize();
            if (!path.startsWith(features)) {
                throw new IllegalArgumentException("Path '" + spec + "' is not inside the features directory");
            }
            if (!Files.exists(path)) {
                throw new IllegalArgumentException("Path '" + spec + "' does not exist in the workspace");
            }
            if (!lines.isEmpty() && !Files.isRegularFile(path)) {
                throw new IllegalArgumentException("Path '" + spec + "' is not a feature file");
            }
            selected.add(FeatureWithLines.create(path.toUri(), lines));
        }
        return selected;
    }

    /** @throws IllegalArgumentException if the expression is invalid */
    Expression expression() {
        if (tags == null) {
            return null;
        }
        try {
            return TagExpressionParser.parse(tags);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid tag expression '" + tags + "': " + e.getMessage(), e);
        }
    }
}
