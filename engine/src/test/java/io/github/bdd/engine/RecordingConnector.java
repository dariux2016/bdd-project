package io.github.bdd.engine;

import io.github.bdd.plugin.Action;
import io.github.bdd.plugin.ActionKind;
import io.github.bdd.plugin.Arguments;
import io.github.bdd.plugin.ConfigProperty;
import io.github.bdd.plugin.ConfigType;
import io.github.bdd.plugin.Connector;
import io.github.bdd.plugin.LocalContainer;
import io.github.bdd.plugin.Parameter;
import io.github.bdd.plugin.ParameterType;
import io.github.bdd.plugin.Phrase;
import io.github.bdd.plugin.StepArgument;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A connector that notes what its actions receive and which containers it starts, installed in
 * tests to see what reaches a connector. It is the only one that can start containers.
 */
public final class RecordingConnector implements Connector {

    /** What happened, in order; tests clear it before an execution. */
    public static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    private static final Parameter SOURCE = new Parameter("source", ParameterType.SOURCE);

    @Override
    public String sourceType() {
        return "recording";
    }

    @Override
    public List<ConfigProperty> configSchema() {
        return List.of(new ConfigProperty("url", ConfigType.STRING, true));
    }

    @Override
    public List<Action> actions() {
        return List.of(
                new Action("table", ActionKind.WRITE, List.of(SOURCE), arguments -> {
                    EVENTS.add("table " + arguments.table());
                    return List.of();
                }, StepArgument.TABLE),
                new Action("text", ActionKind.WRITE, List.of(SOURCE), arguments -> {
                    EVENTS.add("text " + arguments.text());
                    return List.of();
                }, StepArgument.DOC_STRING),
                new Action("file", ActionKind.WRITE,
                        List.of(SOURCE, new Parameter("file", ParameterType.FILE)), this::file),
                new Action("url", ActionKind.READ, List.of(SOURCE), arguments -> {
                    EVENTS.add("url " + arguments.source("source").config().get("url"));
                    return List.of();
                }));
    }

    private List<?> file(Arguments arguments) {
        EVENTS.add("file " + arguments.document("file").name() + " " + arguments.document("file").text().strip());
        return List.of();
    }

    @Override
    public List<Phrase> defaultPhrases() {
        List<Phrase> phrases = new ArrayList<>();
        phrases.add(new Phrase("these rows are recorded on {source}", "table"));
        phrases.add(new Phrase("this text is recorded on {source}", "text"));
        phrases.add(new Phrase("the file {file} is recorded on {source}", "file"));
        phrases.add(new Phrase("the url of {source} is recorded", "url"));
        return phrases;
    }

    @Override
    public LocalContainer startContainer(String image) {
        EVENTS.add("start " + image);
        return new LocalContainer() {
            @Override
            public Map<String, Object> config() {
                return Map.of("url", "container://" + image);
            }

            @Override
            public void close() {
                EVENTS.add("stop " + image);
            }
        };
    }
}
