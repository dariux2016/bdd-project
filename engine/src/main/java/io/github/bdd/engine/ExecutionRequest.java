package io.github.bdd.engine;

import java.nio.file.Path;

/**
 * @param workspace directory holding the features to execute, under {@code features/}
 */
public record ExecutionRequest(Path workspace) {
}
