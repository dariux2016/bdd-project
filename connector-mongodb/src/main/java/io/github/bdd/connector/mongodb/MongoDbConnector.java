package io.github.bdd.connector.mongodb;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
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
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Acts on MongoDB collections.
 *
 * <p>A source sets {@code database} and either {@code uri}, the connection string of a running
 * MongoDB, or {@code image}, the container image of a MongoDB that this connector starts when the
 * source is first used and stops when the process ends, so the container survives across the
 * executions of a server. Filters and documents are written in MongoDB Extended JSON, so that an
 * ObjectId is {@code {"$oid": "..."}}, a date {@code {"$date": "..."}} and a decimal
 * {@code {"$numberDecimal": "..."}}. The records observed have the JSON form of {@link Records}.
 */
public final class MongoDbConnector implements Connector {

    private static final Parameter SOURCE = new Parameter("source", ParameterType.SOURCE);
    private static final Parameter COLLECTION = new Parameter("collection", ParameterType.STRING);
    private static final Parameter FILTER = new Parameter("filter", ParameterType.STRING);

    private static final int PORT = 27017;

    /** One client per connection string, which is thread-safe and pools its own connections. */
    private static final Map<String, MongoClient> CLIENTS = new ConcurrentHashMap<>();
    /** One container per image. */
    private static final Map<String, GenericContainer<?>> CONTAINERS = new ConcurrentHashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            CLIENTS.values().forEach(MongoClient::close);
            CONTAINERS.values().forEach(GenericContainer::stop);
        }));
    }

    @Override
    public String sourceType() {
        return "mongodb";
    }

    @Override
    public List<ConfigProperty> configSchema() {
        return List.of(
                new ConfigProperty("database", ConfigType.STRING, true),
                new ConfigProperty("uri", ConfigType.STRING, false),
                new ConfigProperty("image", ConfigType.STRING, false));
    }

    @Override
    public List<Action> actions() {
        return List.of(
                new Action("clear", ActionKind.WRITE, List.of(SOURCE, COLLECTION), this::clear),
                new Action("insertText", ActionKind.WRITE,
                        List.of(SOURCE, COLLECTION, new Parameter("documents", ParameterType.TEXT)),
                        this::insertText),
                new Action("insertFile", ActionKind.WRITE,
                        List.of(SOURCE, COLLECTION, new Parameter("file", ParameterType.FILE)),
                        this::insertFile),
                new Action("documents", ActionKind.READ, List.of(SOURCE, COLLECTION), this::documents),
                new Action("matching", ActionKind.READ, List.of(SOURCE, COLLECTION, FILTER), this::matching));
    }

    @Override
    public List<Phrase> defaultPhrases() {
        List<Phrase> phrases = new ArrayList<>();
        phrases.add(new Phrase("the MongoDB collection {collection} of source {source} is emptied", "clear"));
        phrases.add(new Phrase("the documents below are inserted in the MongoDB collection {collection} "
                + "of source {source}", "insertText"));
        phrases.add(new Phrase("the documents of the file {file} are inserted in the MongoDB collection "
                + "{collection} of source {source}", "insertFile"));
        phrases.add(italian("la collection MongoDB {collection} della sorgente {source} è svuotata", "clear",
                null));
        phrases.add(italian("i documenti qui sotto sono inseriti nella collection MongoDB {collection} "
                + "della sorgente {source}", "insertText", null));
        phrases.add(italian("i documenti del file {file} sono inseriti nella collection MongoDB "
                + "{collection} della sorgente {source}", "insertFile", null));

        phrases.addAll(english("documents", ""));
        phrases.addAll(english("matching", " matching the filter {filter}"));
        phrases.addAll(italian("documents", ""));
        phrases.addAll(italian("matching", " corrispondente al filtro {filter}"));
        return phrases;
    }

    /** The verifications of the English phrases, which read with {@code action}. */
    private static List<Phrase> english(String action, String filter) {
        String collection = " in the MongoDB collection {collection} of source {source}" + filter;
        String of = " of the MongoDB collection {collection} of source {source}" + filter;
        return List.of(
                english("a document exists" + collection, action, Verification.EXISTS),
                english("a document exists" + collection + " within {timeout} second(s)", action,
                        Verification.EXISTS),
                english("no document exists" + collection, action, Verification.NOT_EXISTS),
                english("no document exists" + collection + " for {timeout} second(s)", action,
                        Verification.NOT_EXISTS),
                english("exactly {count} document(s) exist(s)" + collection, action, Verification.COUNT_EXACTLY),
                english("at least {count} document(s) exist(s)" + collection, action,
                        Verification.COUNT_AT_LEAST),
                english("at most {count} document(s) exist(s)" + collection, action,
                        Verification.COUNT_AT_MOST),
                english("a document" + of + " equals the JSON", action, Verification.JSON_STRICT),
                english("a document" + of + " equals the JSON ignoring {ignored}", action,
                        Verification.JSON_STRICT),
                english("a document" + of + " contains the JSON", action, Verification.JSON_PARTIAL),
                english("a document" + of + " contains the JSON ignoring {ignored}", action,
                        Verification.JSON_PARTIAL),
                english("I save the {field} of a document" + of + " as {variable}", action, Verification.SAVE),
                english("I save the {field} of a document" + of + " as {variable} within {timeout} second(s)",
                        action, Verification.SAVE));
    }

    private static List<Phrase> italian(String action, String filter) {
        String collection = " nella collection MongoDB {collection} della sorgente {source}" + filter;
        String of = " della collection MongoDB {collection} della sorgente {source}" + filter;
        return List.of(
                italian("esiste un documento" + collection, action, Verification.EXISTS),
                italian("esiste un documento" + collection + " entro {timeout} secondo/secondi", action,
                        Verification.EXISTS),
                italian("non esiste alcun documento" + collection, action, Verification.NOT_EXISTS),
                italian("non esiste alcun documento" + collection + " per {timeout} secondo/secondi", action,
                        Verification.NOT_EXISTS),
                italian("esistono esattamente {count} documenti" + collection, action,
                        Verification.COUNT_EXACTLY),
                italian("esistono almeno {count} documenti" + collection, action, Verification.COUNT_AT_LEAST),
                italian("esistono al massimo {count} documenti" + collection, action,
                        Verification.COUNT_AT_MOST),
                italian("un documento" + of + " è uguale al JSON", action, Verification.JSON_STRICT),
                italian("un documento" + of + " è uguale al JSON ignorando {ignored}", action,
                        Verification.JSON_STRICT),
                italian("un documento" + of + " contiene il JSON", action, Verification.JSON_PARTIAL),
                italian("un documento" + of + " contiene il JSON ignorando {ignored}", action,
                        Verification.JSON_PARTIAL),
                italian("salvo {field} di un documento" + of + " come {variable}", action, Verification.SAVE),
                italian("salvo {field} di un documento" + of + " come {variable} entro {timeout} secondo/secondi",
                        action, Verification.SAVE));
    }

    private static Phrase english(String pattern, String action, Verification verification) {
        return new Phrase(pattern, action, verification);
    }

    private static Phrase italian(String pattern, String action, Verification verification) {
        return new Phrase(pattern, action, verification, Phrase.ITALIAN);
    }

    private List<?> clear(Arguments arguments) {
        collection(arguments).deleteMany(new BsonDocument());
        return List.of();
    }

    private List<?> insertText(Arguments arguments) {
        return insert(arguments, arguments.string("documents"));
    }

    private List<?> insertFile(Arguments arguments) {
        return insert(arguments, arguments.string("file"));
    }

    /** @param json one document or an array of them, in Extended JSON */
    private List<?> insert(Arguments arguments, String json) {
        List<BsonDocument> documents = new ArrayList<>();
        try {
            if (json.strip().startsWith("[")) {
                for (BsonValue value : BsonArray.parse(json)) {
                    if (!value.isDocument()) {
                        throw new IllegalArgumentException("expected an array of documents but it holds "
                                + value.getBsonType());
                    }
                    documents.add(value.asDocument());
                }
            } else {
                documents.add(BsonDocument.parse(json));
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "The documents to insert are not valid JSON: " + e.getMessage(), e);
        }
        if (!documents.isEmpty()) {
            collection(arguments).insertMany(documents);
        }
        return List.of();
    }

    private List<?> documents(Arguments arguments) {
        return find(arguments, new BsonDocument());
    }

    private List<?> matching(Arguments arguments) {
        BsonDocument filter;
        try {
            filter = BsonDocument.parse(arguments.string("filter"));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The filter is not a valid MongoDB filter: " + e.getMessage(), e);
        }
        return find(arguments, filter);
    }

    private List<?> find(Arguments arguments, BsonDocument filter) {
        List<Object> records = new ArrayList<>();
        Source source = arguments.source("source");
        try (var cursor = database(source).getCollection(arguments.string("collection")).find(filter).iterator()) {
            while (cursor.hasNext()) {
                records.add(Records.json(cursor.next()));
            }
        }
        return records;
    }

    private MongoCollection<BsonDocument> collection(Arguments arguments) {
        return database(arguments.source("source"))
                .getCollection(arguments.string("collection"), BsonDocument.class);
    }

    private MongoDatabase database(Source source) {
        return client(source).getDatabase((String) source.config().get("database"));
    }

    private static MongoClient client(Source source) {
        String uri = (String) source.config().get("uri");
        String image = (String) source.config().get("image");
        if (uri == null && image == null) {
            throw new IllegalStateException(
                    "Source '" + source.name() + "' sets neither 'uri' nor 'image': one is needed to reach MongoDB");
        }
        if (uri == null) {
            uri = start(image);
        }
        return CLIENTS.computeIfAbsent(uri, MongoClients::create);
    }

    /** @return the connection string of the container of the image, which is started if need be */
    private static String start(String image) {
        GenericContainer<?> container = CONTAINERS.computeIfAbsent(image, name -> {
            GenericContainer<?> started = new GenericContainer<>(DockerImageName.parse(name))
                    .withExposedPorts(PORT)
                    .waitingFor(Wait.forLogMessage(".*[Ww]aiting for connections.*", 1));
            started.start();
            return started;
        });
        return "mongodb://" + container.getHost() + ":" + container.getMappedPort(PORT);
    }
}
