package io.github.bdd.engine;

import io.cucumber.plugin.EventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.TestCaseStarted;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The values a step can cite as <code>${name}</code>, which hold only in the scenario that defines
 * them.
 *
 * <ul>
 *   <li><code>${name}</code>: a variable saved by a step or generated earlier in the scenario;
 *   <li><code>${env:NAME}</code>: an environment variable, whose syntax differs from that of a
 *       variable so that the two cannot be mistaken;
 *   <li><code>${uuid:name}</code>, <code>${now:name}</code>, <code>${now+1d:name}</code>,
 *       <code>${random:name}</code>: a generated value, kept under that name, so that citing the
 *       same name again in the scenario gives the same value while another scenario gets another.
 * </ul>
 */
final class Variables implements EventListener {

    /** What a step can cite without being defined: nothing, for a context that never executes. */
    static final Variables NONE = new Variables(Map.of());

    static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([^}]*)}");
    private static final Pattern GENERATOR = Pattern.compile("(uuid|now|random)([+-]\\d+(?:ms|s|m|h|d))?");
    private static final Pattern OFFSET = Pattern.compile("([+-])(\\d+)(ms|s|m|h|d)");

    private final Map<String, String> environment;
    private Map<String, String> scenario = new HashMap<>();

    Variables(Map<String, String> environment) {
        this.environment = environment;
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestCaseStarted.class, event -> scenario = new HashMap<>());
    }

    /** @throws IllegalArgumentException if the name is not one a step can cite */
    static void requireName(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("'" + name + "' is not a valid variable name: use letters, "
                    + "digits and underscores, not starting with a digit");
        }
    }

    /** @throws IllegalArgumentException if the name is not one a step can cite */
    void save(String name, String value) {
        requireName(name);
        scenario.put(name, value);
    }

    /** Whether a text cites anything, which is then to be resolved. */
    static boolean cites(String text) {
        return text != null && text.contains("${");
    }

    /**
     * @return the text with each reference replaced by its value; {@code null} stays {@code null}
     * @throws IllegalArgumentException if a reference cites a variable that is not defined, or is
     *     not a reference at all
     */
    String resolve(String text) {
        if (!cites(text)) {
            return text;
        }
        return REFERENCE.matcher(text).replaceAll(match -> Matcher.quoteReplacement(value(match.group(1).strip())));
    }

    private String value(String reference) {
        if (reference.startsWith("env:")) {
            String name = reference.substring("env:".length()).strip();
            String value = environment.get(name);
            if (value == null) {
                throw new IllegalArgumentException("Environment variable '" + name + "' is not set");
            }
            return value;
        }
        int colon = reference.indexOf(':');
        if (colon >= 0) {
            return generated(reference.substring(0, colon).strip(), reference.substring(colon + 1).strip(), reference);
        }
        if (GENERATOR.matcher(reference).matches()) {
            throw new IllegalArgumentException("The generator '${" + reference + "}' needs a name to keep its "
                    + "value under, as in '${" + reference + ":name}'");
        }
        if (!NAME.matcher(reference).matches()) {
            throw new IllegalArgumentException("'${" + reference + "}' is not a variable: names use letters, "
                    + "digits and underscores; environment variables are written ${env:NAME}");
        }
        String value = scenario.get(reference);
        if (value == null) {
            throw new IllegalArgumentException("Variable '" + reference + "' is not defined in this scenario");
        }
        return value;
    }

    private String generated(String generator, String name, String reference) {
        if (!GENERATOR.matcher(generator).matches()) {
            throw new IllegalArgumentException("'${" + reference + "}' is not a known generator; they are "
                    + "uuid, now, now+<n><unit> (units ms, s, m, h, d) and random, and ${env:NAME} reads an "
                    + "environment variable");
        }
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("'${" + reference + "}' needs a valid name after the colon");
        }
        String existing = scenario.get(name);
        if (existing != null) {
            return existing;
        }
        String value = generate(generator);
        scenario.put(name, value);
        return value;
    }

    private static String generate(String generator) {
        Matcher match = GENERATOR.matcher(generator);
        match.matches();
        return switch (match.group(1)) {
            case "uuid" -> UUID.randomUUID().toString();
            case "random" -> String.valueOf(ThreadLocalRandom.current().nextInt(1_000_000));
            default -> now(match.group(2)).toString();
        };
    }

    private static Instant now(String offset) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if (offset == null) {
            return now;
        }
        Matcher match = OFFSET.matcher(offset);
        match.matches();
        long amount = Long.parseLong(match.group(2));
        Duration duration = switch (match.group(3)) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            default -> Duration.ofDays(amount);
        };
        return match.group(1).equals("+") ? now.plus(duration) : now.minus(duration);
    }
}
