package io.github.bdd.plugin;

import java.util.List;

@FunctionalInterface
public interface ActionHandler {

    /**
     * Performs the action. Any exception makes the step fail with the exception's message.
     *
     * @return the records the action observed in the source, each a JSON structure made of
     *     {@link java.util.Map}, {@link List}, {@link String}, {@link Number}, {@link Boolean} and
     *     {@code null}, with dates as ISO-8601 text; empty if the action observes nothing. The engine
     *     checks them as the {@link Phrase#verification() verification} of the phrase requires:
     *     handlers do not filter, compare or wait for the records to appear. For such a phrase the
     *     engine performs the action repeatedly within one step, so each call must return what the
     *     source holds at that moment.
     */
    List<?> execute(Arguments arguments) throws Exception;
}
