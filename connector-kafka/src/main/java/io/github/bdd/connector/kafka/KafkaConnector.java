package io.github.bdd.connector.kafka;

import io.github.bdd.plugin.Action;
import io.github.bdd.plugin.ActionKind;
import io.github.bdd.plugin.Arguments;
import io.github.bdd.plugin.ConfigProperty;
import io.github.bdd.plugin.ConfigType;
import io.github.bdd.plugin.Connector;
import io.github.bdd.plugin.Parameter;
import io.github.bdd.plugin.ParameterType;
import io.github.bdd.plugin.Phrase;
import io.github.bdd.plugin.Source;
import io.github.bdd.plugin.Verification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Publishes messages to the topics of a Kafka broker and verifies those the scenario caused.
 *
 * <p>Two kinds of reading are offered, with the same verifications. A <em>message</em> is the
 * payload alone, as JSON when it parses as such and as text otherwise, so that fields are written as
 * JSONPaths of the payload. A <em>Kafka record</em> is the whole of it, as
 * <code>{topic, partition, offset, timestamp, key, headers, value}</code>, for the steps that check
 * the key, a header or the partition; its payload is the {@code value}.
 *
 * <p>Only what arrived after the scenario started is observed, so that the messages left by earlier
 * scenarios, or earlier executions, do not count. Arrival is judged by the timestamp the producer set
 * on the message, so the clock of the producing system should not be far from that of this tool.
 */
public final class KafkaConnector implements Connector {

    private static final Parameter SOURCE = new Parameter("source", ParameterType.SOURCE);
    private static final Parameter TOPIC = new Parameter("topic", ParameterType.STRING);
    private static final Parameter PAYLOAD = new Parameter("payload", ParameterType.STRING);
    private static final Parameter KEY = new Parameter("key", ParameterType.STRING);
    private static final Parameter HEADERS = new Parameter("headers", ParameterType.STRING);
    private static final Parameter PATH = new Parameter("path", ParameterType.STRING);
    private static final Parameter VALUES = new Parameter("values", ParameterType.STRING);

    private static final String PUBLISH = "publish";
    private static final String PUBLISH_WITH_KEY = "publishWithKey";
    private static final String PUBLISH_WITH_HEADERS = "publishWithHeaders";
    private static final String PUBLISH_WITH_KEY_AND_HEADERS = "publishWithKeyAndHeaders";
    private static final String MESSAGES = "messages";
    private static final String RECORDS = "records";
    private static final String IN_ORDER = "inOrder";

    private final Messages messages = new Messages();

    @Override
    public String sourceType() {
        return "kafka";
    }

    /**
     * A source sets either the address of a running broker, or the image of a container to start
     * one from; which of them is missing is told when a step uses the source.
     */
    @Override
    public List<ConfigProperty> configSchema() {
        return List.of(
                new ConfigProperty(Brokers.BOOTSTRAP_SERVERS, ConfigType.STRING, false),
                new ConfigProperty(Brokers.IMAGE, ConfigType.STRING, false));
    }

    @Override
    public List<Action> actions() {
        return List.of(
                new Action(PUBLISH, ActionKind.WRITE, List.of(SOURCE, TOPIC, PAYLOAD),
                        arguments -> publish(arguments, false, false)),
                new Action(PUBLISH_WITH_KEY, ActionKind.WRITE, List.of(SOURCE, TOPIC, KEY, PAYLOAD),
                        arguments -> publish(arguments, true, false)),
                new Action(PUBLISH_WITH_HEADERS, ActionKind.WRITE,
                        List.of(SOURCE, TOPIC, HEADERS, PAYLOAD), arguments -> publish(arguments, false, true)),
                new Action(PUBLISH_WITH_KEY_AND_HEADERS, ActionKind.WRITE,
                        List.of(SOURCE, TOPIC, KEY, HEADERS, PAYLOAD), arguments -> publish(arguments, true, true)),
                new Action(MESSAGES, ActionKind.READ, List.of(SOURCE, TOPIC), arguments ->
                        read(arguments).stream().map(Messages.Received::value).toList()),
                new Action(RECORDS, ActionKind.READ, List.of(SOURCE, TOPIC), arguments ->
                        read(arguments).stream().map(Messages.Received::record).toList()),
                new Action(IN_ORDER, ActionKind.READ, List.of(SOURCE, TOPIC, PATH, VALUES), this::inOrder));
    }

    private List<?> publish(Arguments arguments, boolean keyed, boolean withHeaders) throws Exception {
        messages.publish(Brokers.bootstrapServers(arguments.source("source")), arguments.string("topic"),
                keyed ? arguments.string("key") : null,
                withHeaders ? Messages.headers(arguments.string("headers")) : Map.of(),
                arguments.string("payload"));
        return List.of();
    }

    private List<Messages.Received> read(Arguments arguments) {
        return messages.since(Brokers.bootstrapServers(arguments.source("source")), arguments.string("topic"),
                arguments.scenarioStart());
    }

    /**
     * The order is part of what is observed, so it is checked here rather than by the engine: the
     * values must be those of the field in the messages, in this relative order, others between them
     * being allowed. A failure is retried by the engine like any verification.
     */
    private List<?> inOrder(Arguments arguments) {
        String path = arguments.string("path");
        List<String> expected = Messages.list(arguments.string("values"));
        List<String> observed = read(arguments).stream()
                .map(message -> Messages.text(message.value(), path))
                .toList();
        int next = 0;
        for (String value : observed) {
            if (next < expected.size() && expected.get(next).equals(value)) {
                next++;
            }
        }
        if (next < expected.size()) {
            throw new AssertionError("Expected the messages to arrive in the order of " + path + ": "
                    + expected + ", but '" + expected.get(next) + "' was not observed"
                    + (next == 0 ? "" : " after '" + expected.get(next - 1) + "'")
                    + "\nObserved " + path + " in arrival order: " + observed);
        }
        return List.of(Map.of("inOrder", true));
    }

    @Override
    public List<Phrase> defaultPhrases() {
        List<Phrase> phrases = new ArrayList<>();
        english(phrases);
        italian(phrases);
        return phrases;
    }

    private static void english(List<Phrase> phrases) {
        String where = "the topic {topic} of the Kafka source {source}";
        String in = "published to " + where;
        add(phrases, "en", PUBLISH, "the message {payload} is " + in);
        add(phrases, "en", PUBLISH_WITH_KEY, "the message {payload} with the key {key} is " + in);
        add(phrases, "en", PUBLISH_WITH_HEADERS, "the message {payload} with the headers {headers} is " + in);
        add(phrases, "en", PUBLISH_WITH_KEY_AND_HEADERS,
                "the message {payload} with the key {key} and the headers {headers} is " + in);
        for (String[] noun : new String[][] {{MESSAGES, "message"}, {RECORDS, "Kafka record"}}) {
            String action = noun[0];
            String n = noun[1];
            String on = "on " + where;
            String within = " within {timeout} second(s)";
            String during = " for {timeout} second(s)";
            verification(phrases, "en", action, Verification.EXISTS, "a " + n + " exists " + on, within);
            verification(phrases, "en", action, Verification.NOT_EXISTS, "no " + n + " exists " + on, during);
            verification(phrases, "en", action, Verification.COUNT_EXACTLY,
                    "exactly {count} " + n + "(s) exist(s) " + on, within);
            verification(phrases, "en", action, Verification.COUNT_AT_LEAST,
                    "at least {count} " + n + "(s) exist(s) " + on, within);
            verification(phrases, "en", action, Verification.COUNT_AT_MOST,
                    "at most {count} " + n + "(s) exist(s) " + on, during);
            verification(phrases, "en", action, Verification.JSON_STRICT, "a " + n + " " + on + " equals the JSON",
                    within);
            verification(phrases, "en", action, Verification.JSON_STRICT,
                    "a " + n + " " + on + " equals the JSON ignoring {ignored}", within);
            verification(phrases, "en", action, Verification.JSON_PARTIAL, "a " + n + " " + on + " contains the JSON",
                    within);
            verification(phrases, "en", action, Verification.JSON_PARTIAL,
                    "a " + n + " " + on + " contains the JSON ignoring {ignored}", within);
            verification(phrases, "en", action, Verification.SAVE,
                    "I save the {field} of a " + n + " " + on + " as {variable}", within);
        }
        verification(phrases, "en", IN_ORDER, Verification.EXISTS,
                "the messages on " + where + " arrive in the order of {path}: {values}", " within {timeout} second(s)");
    }

    private static void italian(List<Phrase> phrases) {
        String where = "sul topic {topic} della sorgente Kafka {source}";
        String in = "pubblicato " + where;
        add(phrases, "it", PUBLISH, "il messaggio {payload} viene " + in);
        add(phrases, "it", PUBLISH_WITH_KEY, "il messaggio {payload} con la chiave {key} viene " + in);
        add(phrases, "it", PUBLISH_WITH_HEADERS, "il messaggio {payload} con gli header {headers} viene " + in);
        add(phrases, "it", PUBLISH_WITH_KEY_AND_HEADERS,
                "il messaggio {payload} con la chiave {key} e gli header {headers} viene " + in);
        for (String[] noun : new String[][] {{MESSAGES, "messaggio", "messaggi"}, {RECORDS, "record Kafka",
                "record Kafka"}}) {
            String action = noun[0];
            String one = noun[1];
            String many = noun[2];
            String on = "sul topic {topic} della sorgente Kafka {source}";
            String within = " entro {timeout} secondo/secondi";
            String during = " per {timeout} secondo/secondi";
            verification(phrases, "it", action, Verification.EXISTS, "esiste un " + one + " " + on, within);
            verification(phrases, "it", action, Verification.NOT_EXISTS, "non esiste alcun " + one + " " + on,
                    during);
            verification(phrases, "it", action, Verification.COUNT_EXACTLY,
                    "esistono esattamente {count} " + many + " " + on, within);
            verification(phrases, "it", action, Verification.COUNT_AT_LEAST,
                    "esistono almeno {count} " + many + " " + on, within);
            verification(phrases, "it", action, Verification.COUNT_AT_MOST,
                    "esistono al massimo {count} " + many + " " + on, during);
            verification(phrases, "it", action, Verification.JSON_STRICT,
                    "un " + one + " " + on + " è uguale al JSON", within);
            verification(phrases, "it", action, Verification.JSON_STRICT,
                    "un " + one + " " + on + " è uguale al JSON ignorando {ignored}", within);
            verification(phrases, "it", action, Verification.JSON_PARTIAL,
                    "un " + one + " " + on + " contiene il JSON", within);
            verification(phrases, "it", action, Verification.JSON_PARTIAL,
                    "un " + one + " " + on + " contiene il JSON ignorando {ignored}", within);
            verification(phrases, "it", action, Verification.SAVE,
                    "salvo {field} di un " + one + " " + on + " come {variable}", within);
        }
        verification(phrases, "it", IN_ORDER, Verification.EXISTS,
                "i messaggi " + where + " arrivano nell'ordine di {path}: {values}",
                " entro {timeout} secondo/secondi");
    }

    private static void add(List<Phrase> phrases, String language, String action, String pattern) {
        phrases.add(new Phrase(pattern, action, null, language));
    }

    /** The phrase, and the one that also gives the timeout of the verification. */
    private static void verification(List<Phrase> phrases, String language, String action,
            Verification verification, String pattern, String timeout) {
        phrases.add(new Phrase(pattern, action, verification, language));
        phrases.add(new Phrase(pattern + timeout, action, verification, language));
    }
}
