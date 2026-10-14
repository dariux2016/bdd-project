package io.github.bdd.connector.postgresql;

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
import io.github.bdd.plugin.Verification;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL sources: tables are prepared with rows from a Gherkin table or a file, or with a
 * script, and read back as records for the engine's verifications, either whole or through a query.
 *
 * <p>Column names and table names are folded to lowercase, as PostgreSQL does when they are not
 * quoted; they may hold letters, digits, {@code _} and {@code $}, and a table may be preceded by its
 * schema.
 */
public final class PostgresConnector implements Connector {

    static final String TYPE = "postgresql";

    private static final Parameter SOURCE = new Parameter("source", ParameterType.SOURCE);
    private static final Parameter TABLE = new Parameter("table", ParameterType.STRING);
    private static final Parameter QUERY = new Parameter("query", ParameterType.STRING);
    private static final Parameter FILE = new Parameter("file", ParameterType.FILE);

    @Override
    public String sourceType() {
        return TYPE;
    }

    @Override
    public List<ConfigProperty> configSchema() {
        return List.of(
                new ConfigProperty("url", ConfigType.STRING, true),
                new ConfigProperty("username", ConfigType.STRING, false),
                new ConfigProperty("password", ConfigType.STRING, false));
    }

    @Override
    public List<Action> actions() {
        return List.of(
                new Action("empty", ActionKind.WRITE, List.of(SOURCE, TABLE), arguments -> {
                    Database.empty(arguments.source("source"), arguments.string("table"));
                    return List.of();
                }),
                new Action("insert", ActionKind.WRITE, List.of(SOURCE, TABLE), arguments -> {
                    Database.insert(arguments.source("source"), arguments.string("table"),
                            RowSource.fromTable(arguments.table()));
                    return List.of();
                }, StepArgument.TABLE),
                new Action("insertFile", ActionKind.WRITE, List.of(SOURCE, FILE, TABLE), arguments -> {
                    Database.insert(arguments.source("source"), arguments.string("table"),
                            RowSource.fromDocument(arguments.document("file")));
                    return List.of();
                }),
                new Action("script", ActionKind.WRITE, List.of(SOURCE), arguments -> {
                    Database.script(arguments.source("source"), arguments.text());
                    return List.of();
                }, StepArgument.DOC_STRING),
                new Action("scriptFile", ActionKind.WRITE, List.of(SOURCE, FILE), arguments -> {
                    Database.script(arguments.source("source"), arguments.document("file").text());
                    return List.of();
                }),
                new Action("rows", ActionKind.READ, List.of(SOURCE, TABLE),
                        arguments -> Database.rows(arguments.source("source"), arguments.string("table"))),
                new Action("query", ActionKind.READ, List.of(SOURCE, QUERY),
                        arguments -> Database.query(arguments.source("source"), arguments.string("query"))));
    }

    @Override
    public List<Phrase> defaultPhrases() {
        List<Phrase> phrases = new ArrayList<>();
        english(phrases);
        italian(phrases);
        return phrases;
    }

    private static void english(List<Phrase> phrases) {
        phrases.add(new Phrase("the table {table} of the PostgreSQL source {source} is emptied", "empty"));
        phrases.add(new Phrase("these rows are inserted in table {table} of the PostgreSQL source {source}", "insert"));
        phrases.add(new Phrase("the rows of the file {file} are inserted in table {table} of the PostgreSQL "
                + "source {source}", "insertFile"));
        phrases.add(new Phrase("the script is executed on the PostgreSQL source {source}", "script"));
        phrases.add(new Phrase("the script file {file} is executed on the PostgreSQL source {source}", "scriptFile"));

        String table = "table {table} of the PostgreSQL source {source}";
        verifications(phrases, "rows", Phrase.ENGLISH,
                "a row exists in " + table, " within {timeout} second(s)",
                "no row exists in " + table, " for {timeout} second(s)",
                "{kind} {count} row(s) exist(s) in " + table,
                "a row of " + table, " equals the JSON", " contains the JSON", " ignoring {ignored}",
                "I save the {field} of a row of " + table + " as {variable}", " within {timeout} second(s)");

        String query = "the query {query} on the PostgreSQL source {source}";
        verifications(phrases, "query", Phrase.ENGLISH,
                query + " returns a row", " within {timeout} second(s)",
                query + " returns no row", " for {timeout} second(s)",
                query + " returns {kind} {count} row(s)",
                "a row returned by " + query, " equals the JSON", " contains the JSON", " ignoring {ignored}",
                "I save the {field} of a row returned by " + query + " as {variable}", " within {timeout} second(s)");
    }

    private static void italian(List<Phrase> phrases) {
        phrases.add(new Phrase("la tabella {table} della sorgente PostgreSQL {source} viene svuotata", "empty", null,
                Phrase.ITALIAN));
        phrases.add(new Phrase("queste righe vengono inserite nella tabella {table} della sorgente PostgreSQL "
                + "{source}", "insert", null, Phrase.ITALIAN));
        phrases.add(new Phrase("le righe del file {file} vengono inserite nella tabella {table} della sorgente "
                + "PostgreSQL {source}", "insertFile", null, Phrase.ITALIAN));
        phrases.add(new Phrase("lo script viene eseguito sulla sorgente PostgreSQL {source}", "script", null,
                Phrase.ITALIAN));
        phrases.add(new Phrase("il file di script {file} viene eseguito sulla sorgente PostgreSQL {source}",
                "scriptFile", null, Phrase.ITALIAN));

        String table = "tabella {table} della sorgente PostgreSQL {source}";
        verifications(phrases, "rows", Phrase.ITALIAN,
                "esiste una riga nella " + table, " entro {timeout} secondo/secondi",
                "non esiste alcuna riga nella " + table, " per {timeout} secondo/secondi",
                "esistono {kind} {count} riga/righe nella " + table,
                "una riga della " + table, " è uguale al JSON", " contiene il JSON", " ignorando {ignored}",
                "salvo {field} di una riga della " + table + " come {variable}", " entro {timeout} secondo/secondi");

        String query = "la query {query} sulla sorgente PostgreSQL {source}";
        verifications(phrases, "query", Phrase.ITALIAN,
                query + " restituisce una riga", " entro {timeout} secondo/secondi",
                query + " non restituisce alcuna riga", " per {timeout} secondo/secondi",
                query + " restituisce {kind} {count} riga/righe",
                "una riga restituita dalla query {query} sulla sorgente PostgreSQL {source}",
                " è uguale al JSON", " contiene il JSON", " ignorando {ignored}",
                "salvo {field} di una riga restituita dalla query {query} sulla sorgente PostgreSQL {source} "
                        + "come {variable}", " entro {timeout} secondo/secondi");
    }

    /**
     * The phrases that verify the records an action returns, each with the wording of its language.
     *
     * @param count the pattern of the count phrases, where {@code {kind}} stands for exactly, at least
     *     and at most
     * @param json the start of the JSON phrases, followed by {@code equals} or {@code contains} and
     *     then, optionally, {@code ignoring}
     */
    private static void verifications(List<Phrase> phrases, String action, String language, String exists,
            String existsTimeout, String notExists, String notExistsTimeout, String count, String json,
            String equals, String contains, String ignoring, String save, String saveTimeout) {
        add(phrases, action, language, Verification.EXISTS, exists);
        add(phrases, action, language, Verification.EXISTS, exists + existsTimeout);
        add(phrases, action, language, Verification.NOT_EXISTS, notExists);
        add(phrases, action, language, Verification.NOT_EXISTS, notExists + notExistsTimeout);
        boolean english = language.equals(Phrase.ENGLISH);
        add(phrases, action, language, Verification.COUNT_EXACTLY,
                count.replace("{kind}", english ? "exactly" : "esattamente"));
        add(phrases, action, language, Verification.COUNT_AT_LEAST,
                count.replace("{kind}", english ? "at least" : "almeno"));
        add(phrases, action, language, Verification.COUNT_AT_MOST,
                count.replace("{kind}", english ? "at most" : "al massimo"));
        add(phrases, action, language, Verification.JSON_STRICT, json + equals);
        add(phrases, action, language, Verification.JSON_STRICT, json + equals + ignoring);
        add(phrases, action, language, Verification.JSON_PARTIAL, json + contains);
        add(phrases, action, language, Verification.JSON_PARTIAL, json + contains + ignoring);
        add(phrases, action, language, Verification.SAVE, save);
        add(phrases, action, language, Verification.SAVE, save + saveTimeout);
    }

    private static void add(List<Phrase> phrases, String action, String language, Verification verification,
            String pattern) {
        phrases.add(new Phrase(pattern, action, verification, language));
    }

    @Override
    public LocalContainer startContainer(String image) {
        PostgreSQLContainer container = new PostgreSQLContainer(DockerImageName.parse(image)
                .asCompatibleSubstituteFor("postgres"));
        container.start();
        return new LocalContainer() {
            @Override
            public Map<String, Object> config() {
                return Map.of(
                        "url", container.getJdbcUrl(),
                        "username", container.getUsername(),
                        "password", container.getPassword());
            }

            @Override
            public void close() {
                container.stop();
            }
        };
    }
}
