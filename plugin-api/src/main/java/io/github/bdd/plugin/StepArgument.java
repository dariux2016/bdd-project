package io.github.bdd.plugin;

/** The table or doc string a step gives to an action, besides the parameters its phrase names. */
public enum StepArgument {
    /** The action takes none. */
    NONE,
    /** The step must have a data table; the action receives its rows, header row included. */
    TABLE,
    /** The step must have a doc string; the action receives its content. */
    DOC_STRING,
    /** The step may have a data table, a doc string or neither; the action reads whichever there is. */
    OPTIONAL
}
