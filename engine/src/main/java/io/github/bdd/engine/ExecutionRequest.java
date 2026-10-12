package io.github.bdd.engine;

import java.nio.file.Path;
import java.util.Map;

/**
 * @param workspace directory holding the features to execute, under {@code features/}, and the
 *     configuration of the sources, in {@code sources.yaml}
 * @param profile the profile of {@code sources.yaml} whose sources the features act on;
 *     {@code null} only for a workspace without {@code sources.yaml}
 * @param environment the environment variables that resolve the <code>${NAME}</code> values of the
 *     configuration; not copied, so that the lookup stays case-insensitive where the platform's is
 */
public record ExecutionRequest(Path workspace, String profile, Map<String, String> environment) {

    /** A request whose configuration is resolved from the environment of this process. */
    public ExecutionRequest(Path workspace, String profile) {
        this(workspace, profile, System.getenv());
    }
}
