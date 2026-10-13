package io.github.bdd.engine;

import com.jayway.jsonpath.JsonPath;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.github.bdd.plugin.Verification;

import java.util.List;
import java.util.regex.Pattern;

/** Checks the records an action observed, as the verification of the phrase requires. */
final class Verifier {

    /** The commas that separate the ignored paths, as opposed to those within the brackets of one. */
    private static final Pattern SEPARATOR = Pattern.compile(",(?![^\\[]*])");

    private final Verification verification;
    private final CurrentStep currentStep;
    private final Diagnostics diagnostics;

    Verifier(Verification verification, CurrentStep currentStep, Diagnostics diagnostics) {
        this.verification = verification;
        this.currentStep = currentStep;
        this.diagnostics = diagnostics;
    }

    /**
     * Whether the verification must hold for as long as it is retried, instead of being retried
     * until it holds: nothing that appears later can satisfy it, only break it.
     */
    boolean lasting() {
        return verification == Verification.NOT_EXISTS || verification == Verification.COUNT_AT_MOST;
    }

    /**
     * @param count the <code>{count}</code> of the step; {@code null} if the phrase has none
     * @param ignored the <code>{ignored}</code> of the step; {@code null} if the phrase has none
     * @param masked the fields of the records that a failure does not show
     * @param stepArgument the table or doc string of the step; {@code null} if it has neither
     * @throws IllegalArgumentException if the step is not written as the verification requires
     * @throws AssertionError if the records are not as expected
     */
    void verify(List<?> records, Integer count, String ignored, List<JsonPath> masked, Object stepArgument) {
        switch (verification) {
            case EXISTS, NOT_EXISTS, COUNT_EXACTLY, COUNT_AT_LEAST, COUNT_AT_MOST ->
                    verifyCount(records, count, masked, stepArgument);
            case JSON_STRICT, JSON_PARTIAL -> verifyJson(records, ignored, masked, stepArgument);
        }
    }

    private void verifyCount(List<?> records, Integer count, List<JsonPath> masked, Object stepArgument) {
        if (stepArgument instanceof DocString) {
            throw new IllegalArgumentException("This step takes a filter table, not a doc string");
        }
        Filter filter = stepArgument instanceof DataTable table
                ? Filter.parse(table.cells(), currentStep::tableRow)
                : Filter.NONE;
        long matching = records.stream().filter(filter::matches).count();

        boolean verified = switch (verification) {
            case EXISTS -> matching > 0;
            case NOT_EXISTS -> matching == 0;
            case COUNT_EXACTLY -> matching == count;
            case COUNT_AT_LEAST -> matching >= count;
            case COUNT_AT_MOST -> matching <= count;
            case JSON_STRICT, JSON_PARTIAL -> throw new IllegalStateException();
        };
        if (!verified) {
            String expected = switch (verification) {
                case EXISTS -> "a matching record";
                case NOT_EXISTS -> "no matching record";
                case COUNT_EXACTLY -> "exactly " + count + " matching " + noun(count);
                case COUNT_AT_LEAST -> "at least " + count + " matching " + noun(count);
                case COUNT_AT_MOST -> "at most " + count + " matching " + noun(count);
                case JSON_STRICT, JSON_PARTIAL -> throw new IllegalStateException();
            };
            throw new AssertionError("Expected " + expected + ", but " + matching + " of the " + records.size()
                    + " observed records match\n" + diagnostics.show(records, masked));
        }
    }

    private static String noun(int count) {
        return count == 1 ? "record" : "records";
    }

    private void verifyJson(List<?> records, String ignored, List<JsonPath> masked, Object stepArgument) {
        if (!(stepArgument instanceof DocString docString)) {
            throw new IllegalArgumentException("This step needs the expected JSON as a doc string");
        }
        List<JsonPath> ignoredPaths = ignored == null ? List.of() : SEPARATOR.splitAsStream(ignored)
                .filter(path -> !path.isBlank())
                .map(path -> Json.path(path.strip()))
                .toList();
        Object expected;
        try {
            expected = Json.without(Json.parse(docString.getContent()), ignoredPaths);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The expected JSON is not valid: " + e.getMessage());
        }

        boolean strict = verification == Verification.JSON_STRICT;
        for (Object record : records) {
            if (Json.difference(expected, Json.without(record, ignoredPaths), strict) == null) {
                return;
            }
        }
        String relation = strict ? "equal" : "contain";
        String summary;
        if (records.size() == 1) {
            // The difference quotes the record, so it is found on the record as it is shown; the
            // expected JSON is masked alike, so that a masked field is not blamed for what hides it.
            Object shown = Json.mask(records.get(0), masked, Diagnostics.MASK);
            Object expectedShown = Json.mask(expected, masked, Diagnostics.MASK);
            String difference = Json.difference(expectedShown, Json.without(shown, ignoredPaths), strict);
            summary = "Expected the observed record to " + relation + " the JSON, but "
                    + (difference != null ? diagnostics.clip(difference) : "a masked field differs");
        } else {
            summary = "Expected a record to " + relation + " the JSON, but none of the " + records.size()
                    + " observed records does";
        }
        throw new AssertionError(summary + "\n" + diagnostics.show(records, masked));
    }
}
