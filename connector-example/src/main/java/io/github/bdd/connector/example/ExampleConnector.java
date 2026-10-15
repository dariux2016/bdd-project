package io.github.bdd.connector.example;

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
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * An example of an external connector: in-memory stores, one per source, of records written as
 * YAML. It parses them with its own version of SnakeYAML, which is not the one of the tool, and can
 * tell which version it sees.
 *
 * <pre>
 * # sources.yaml
 * profiles:
 *   local:
 *     sources:
 *       notes:
 *         type: example
 *         config:
 *           capacity: 100   # optional: how many records the store holds at most
 * </pre>
 */
public final class ExampleConnector implements Connector {

    private static final Parameter SOURCE = new Parameter("source", ParameterType.SOURCE);
    private static final String CAPACITY = "capacity";

    private final Map<String, List<Object>> stores = new HashMap<>();

    @Override
    public String sourceType() {
        return "example";
    }

    @Override
    public List<ConfigProperty> configSchema() {
        return List.of(new ConfigProperty(CAPACITY, ConfigType.INTEGER, false));
    }

    @Override
    public List<Action> actions() {
        return List.of(
                new Action("load", ActionKind.WRITE,
                        List.of(SOURCE, new Parameter("yaml", ParameterType.TEXT)), this::load),
                new Action("records", ActionKind.READ, List.of(SOURCE), this::records),
                new Action("library", ActionKind.READ, List.of(SOURCE), arguments -> List.of(library())));
    }

    @Override
    public List<Phrase> defaultPhrases() {
        Phrase load = new Phrase("the example source {source} holds the YAML records", "load").withId("example.load");
        Phrase exists = new Phrase("a record exists in the example source {source}", "records", Verification.EXISTS)
                .withId("example.exists");
        Phrase library = new Phrase("the YAML library of the example source {source} matches", "library",
                Verification.EXISTS).withId("example.library");
        return List.of(load, exists, library,
                load.in(Phrase.ITALIAN, "la sorgente di esempio {source} contiene i record YAML"),
                exists.in(Phrase.ITALIAN, "esiste un record nella sorgente di esempio {source}"),
                library.in(Phrase.ITALIAN, "la libreria YAML della sorgente di esempio {source} corrisponde"));
    }

    /** Each scenario starts from empty stores. */
    @Override
    public void scenarioStarted() {
        stores.clear();
    }

    private List<?> load(Arguments arguments) {
        Source source = arguments.source("source");
        if (!arguments.has("yaml")) {
            throw new IllegalArgumentException("This step needs the YAML records as a doc string");
        }
        Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(arguments.string("yaml"));
        List<Object> records = store(source);
        if (parsed instanceof List<?> list) {
            records.addAll(list);
        } else {
            records.add(parsed);
        }
        Integer capacity = (Integer) source.config().get(CAPACITY);
        if (capacity != null && records.size() > capacity) {
            throw new IllegalStateException("The example source '" + source.name() + "' holds at most " + capacity
                    + " records, but now has " + records.size());
        }
        return List.of();
    }

    private List<?> records(Arguments arguments) {
        return List.copyOf(store(arguments.source("source")));
    }

    private List<Object> store(Source source) {
        return stores.computeIfAbsent(source.name(), name -> new ArrayList<>());
    }

    /** The YAML library this connector parses with, as its class loader finds it. */
    private static Map<String, Object> library() {
        Properties properties = new Properties();
        try (InputStream pom = Yaml.class.getClassLoader()
                .getResourceAsStream("META-INF/maven/org.yaml/snakeyaml/pom.properties")) {
            if (pom != null) {
                properties.load(pom);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.of("name", "snakeyaml", "version", properties.getProperty("version", "unknown"));
    }
}
