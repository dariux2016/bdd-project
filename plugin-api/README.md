# plugin-api: scrivere un connettore

`plugin-api` è il contratto dei connettori, pubblicato come artefatto a sé (`io.github.bdd:plugin-api`): un connettore dipende solo da questo, mai dal motore. Il modulo `connector-example` è un connettore esterno completo da usare come modello.

## 1. Dipendenze

Il contratto lo fornisce lo strumento, quindi va in scope `provided`; le altre dipendenze sono del connettore.

```xml
<dependency>
    <groupId>io.github.bdd</groupId>
    <artifactId>plugin-api</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
```

## 2. Il connettore

Una classe che implementa `io.github.bdd.plugin.Connector`, con un costruttore pubblico senza argomenti. Il motore ne crea un'istanza per esecuzione.

| Metodo | Cosa dichiara |
| --- | --- |
| `sourceType()` | il tipo di sorgente usato in `sources.yaml` (`type: example`); unico tra i connettori installati |
| `configSchema()` | i parametri di `config` della sorgente (`ConfigProperty`: nome, tipo, obbligatorio); il motore rifiuta gli altri e converte i valori al tipo dichiarato |
| `actions()` | le azioni: nome, `ActionKind.READ` o `WRITE`, parametri tipizzati (`Parameter`) e il codice che le esegue |
| `defaultPhrases()` | le frasi del catalogo, almeno una per lingua (`en`, `it`), ognuna legata a un'azione |
| `scenarioStarted()` | facoltativo: dimentica ciò che lo scenario precedente ha fatto |

- Un'azione `WRITE` non viene eseguita su una sorgente `readOnly`: il controllo è del motore.
- Un'azione di lettura restituisce i record osservati come strutture JSON (`Map`, `List`, stringhe, numeri, booleani). Filtri, operatori, confronto JSON, polling, timeout, troncamento e mascheramento li applica il motore se la frase dichiara una `Verification`.
- I parametri di tipo `SOURCE` ricevono la `Source` con il suo `config`; `TEXT` e `TABLE` ricevono il testo multilinea e la tabella dello step, che il pattern non nomina.
- Un pattern è una Cucumber Expression che nomina ogni parametro dell'azione tra graffe: `a record exists in the example source {source}`.
- `Phrase.withId("example.exists")` dà alla frase un identificativo, condiviso dalle sue versioni nelle varie lingue: una frase `pending` del workspace con lo stesso identificativo diventa attiva quando il connettore è installato.

```java
public final class ExampleConnector implements Connector {

    public String sourceType() {
        return "example";
    }

    public List<ConfigProperty> configSchema() {
        return List.of(new ConfigProperty("capacity", ConfigType.INTEGER, false));
    }

    public List<Action> actions() {
        return List.of(new Action("records", ActionKind.READ,
                List.of(new Parameter("source", ParameterType.SOURCE)), this::records));
    }

    public List<Phrase> defaultPhrases() {
        Phrase exists = new Phrase("a record exists in the example source {source}", "records",
                Verification.EXISTS).withId("example.exists");
        return List.of(exists, exists.in(Phrase.ITALIAN, "esiste un record nella sorgente di esempio {source}"));
    }
    ...
}
```

## 3. La registrazione

Il connettore si dichiara per `ServiceLoader`, nel file `META-INF/services/io.github.bdd.plugin.Connector` del jar, con il nome completo della classe:

```
io.github.bdd.connector.example.ExampleConnector
```

## 4. L'installazione

Il plugin va nella cartella `plugins` del workspace, e lo strumento lo carica all'avvio di ogni esecuzione:

- un jar direttamente in `plugins/` è un plugin a sé, adatto a un connettore senza dipendenze o con le dipendenze incluse (shaded);
- una cartella in `plugins/` è un plugin fatto di tutti i suoi jar: il connettore e le sue dipendenze, così come li copia `maven-dependency-plugin` (vedi il `pom.xml` di `connector-example`, che assembla `target/plugin/example`).

```
workspace/
  plugins/
    example/
      connector-example-0.1.0-SNAPSHOT-plugin.jar
      snakeyaml-2.2.jar
```

Ogni plugin ha un class loader suo, che vede la piattaforma Java, il contratto (`io.github.bdd.plugin`) e i propri jar, nient'altro: le sue dipendenze non entrano in conflitto con quelle dello strumento né con quelle degli altri plugin. Per lo stesso motivo il package di un connettore non può essere `io.github.bdd.plugin`. Il class loader di contesto del thread resta quello dello strumento: una libreria che lo usa per trovare le proprie classi va configurata con il class loader del connettore (`getClass().getClassLoader()`).

L'avvio si ferma con un errore che nomina il plugin se un jar non si può leggere, se un connettore dichiarato non si può caricare, se un plugin non contiene connettori o se due connettori, inclusi quelli dello strumento, dichiarano lo stesso tipo di sorgente.
