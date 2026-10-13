package io.github.bdd.engine;

import java.nio.file.Path;
import java.util.Map;

/**
 * @param workspace directory holding the features to execute, under {@code features/}, the
 *     configuration of the sources, in {@code sources.yaml}, and optionally the settings of the
 *     execution, in {@code settings.yaml}; the reports are written under {@code reports/}
 * @param profile the profile of {@code sources.yaml} whose sources the features act on;
 *     {@code null} only for a workspace without {@code sources.yaml}
 * @param environment the environment variables that resolve the <code>${NAME}</code> values of the
 *     configuration; not copied, so that the lookup stays case-insensitive where the platform's is
 * @param selection what to execute of the workspace; {@code null} for all of it
 */
public record ExecutionRequest(Path workspace, String profile, Map<String, String> environment,
        Selection selection) {

    public ExecutionRequest {
        selection = selection == null ? Selection.ALL : selection;
    }

    /** A request for the whole workspace. */
    public ExecutionRequest(Path workspace, String profile, Map<String, String> environment) {
        this(workspace, profile, environment, Selection.ALL);
    }

    /** A request for the whole workspace, with the configuration resolved from the environment of this process. */
    public ExecutionRequest(Path workspace, String profile) {
        this(workspace, profile, System.getenv());
    }
}
