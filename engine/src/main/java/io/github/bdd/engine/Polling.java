package io.github.bdd.engine;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How long and how often a verification is retried, since the data of an asynchronous system may
 * appear some time after the action that causes it.
 *
 * @param timeout how long a verification is retried for; zero for a single attempt
 * @param interval the pause between an attempt and the next
 */
record Polling(Duration timeout, Duration interval) {

    private static final YamlFile YAML = Settings.YAML;
    private static final Pattern DURATION = Pattern.compile("(\\d+) ?(ms|s)");

    private static final Polling DEFAULT = new Polling(Duration.ofSeconds(10), Duration.ofMillis(500));

    /** A check on the state of a source at the time it is called. */
    @FunctionalInterface
    interface Check {

        /** @throws AssertionError if the state is not as expected; any other exception is not retried */
        void run() throws Exception;
    }

    /**
     * Reads the polling of the verifications from the workspace's {@code settings.yaml}; what the
     * file leaves out, and a workspace without it, has the defaults.
     *
     * @throws ConfigurationException if the settings are invalid
     */
    static Polling load(Path workspace) {
        Map<String, Object> verification = Settings.section(workspace, "verification");
        YAML.allowOnly(verification, "verification", "timeout", "pollInterval");

        Duration timeout = duration(verification.get("timeout"), "verification.timeout", DEFAULT.timeout());
        Duration interval = duration(
                verification.get("pollInterval"), "verification.pollInterval", DEFAULT.interval());
        if (interval.isZero()) {
            throw YAML.invalid("verification.pollInterval",
                    "expected more than zero but was " + YamlFile.show(verification.get("pollInterval")));
        }
        return new Polling(timeout, interval);
    }

    private static Duration duration(Object value, String path, Duration byDefault) {
        if (value == null) {
            return byDefault;
        }
        Matcher matcher = value instanceof String text ? DURATION.matcher(text.strip()) : null;
        if (matcher == null || !matcher.matches()) {
            throw YAML.invalid(path, "expected a duration such as 10s or 500ms but was " + YamlFile.show(value));
        }
        try {
            long amount = Long.parseLong(matcher.group(1));
            return matcher.group(2).equals("s") ? Duration.ofSeconds(amount) : Duration.ofMillis(amount);
        } catch (NumberFormatException e) {
            throw YAML.invalid(path, "the duration " + YamlFile.show(value) + " is too long");
        }
    }

    /** This polling with the timeout a step gives in place of the default one. */
    Polling within(Duration timeout) {
        return new Polling(timeout, interval);
    }

    /**
     * Repeats a check until the timeout expires, with one last attempt at that moment.
     *
     * @param lasting whether the check must hold for the whole time, as when verifying that
     *     something does not happen, rather than be retried until it holds
     * @throws AssertionError if the check never held or, when lasting, as soon as it does not
     */
    void run(Check check, boolean lasting) throws Exception {
        long start = System.nanoTime();
        while (true) {
            AssertionError failure = null;
            try {
                check.run();
            } catch (AssertionError e) {
                failure = e;
            }
            if (failure == null && !lasting) {
                return;
            }
            if (failure != null && lasting) {
                throw failure;
            }
            Duration left = timeout.minusNanos(System.nanoTime() - start);
            if (left.isZero() || left.isNegative()) {
                if (failure == null) {
                    return;
                }
                throw timeout.isZero() ? failure : new AssertionError(
                        withWaited(failure.getMessage(), timeout), failure);
            }
            sleep(left.compareTo(interval) < 0 ? left : interval);
        }
    }

    /** The waiting is told on the first line of a message, ahead of the data the failure shows. */
    private static String withWaited(String message, Duration timeout) {
        int end = message.indexOf('\n');
        String waited = " (after waiting " + show(timeout) + ")";
        return end < 0 ? message + waited : message.substring(0, end) + waited + message.substring(end);
    }

    static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The execution was interrupted while waiting");
        }
    }

    private static String show(Duration duration) {
        return duration.toMillisPart() == 0 ? duration.toSeconds() + " s" : duration.toMillis() + " ms";
    }
}
