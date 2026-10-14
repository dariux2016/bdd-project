package io.github.bdd.plugin;

/**
 * The content of a file of the workspace, as given to an action by a {@link ParameterType#FILE}
 * parameter.
 *
 * @param name the path the step wrote, relative to the workspace; its extension tells the format
 * @param text the content of the file, read as UTF-8
 */
public record Document(String name, String text) {
}
