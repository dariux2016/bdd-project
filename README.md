# bdd-project

Strumento di test BDD black-box basato su Gherkin, riusabile su qualsiasi progetto: frasi in linguaggio naturale per verificare sorgenti dati configurabili (Kafka, PostgreSQL, MongoDB, HTTP) tramite connettori a plugin.

Stato: in costruzione. La specifica è nella issue #1.

## Moduli

- `plugin-api`: il contratto dei connettori.
- `engine`: esegue le feature di un workspace con le frasi dei connettori installati.
- `connector-fake`: connettore finto in memoria, supporto di test del motore.
- `connector-mongodb`: connettore per le collection MongoDB (vedi "Connettore MongoDB").
- `cli`: punto d'ingresso; `run [workspace] [--profile <nome>] [--path <percorso>]... [--tags <espressione>]` esegue le feature in `<workspace>/features` sulle sorgenti del profilo scelto; `serve [workspace] [--port <n>]` avvia l'interfaccia web (vedi sotto).
- `server`: API HTTP sulla cartella `features` di un workspace e UI statica, nello stesso artefatto.

## Interfaccia web (`serve`)

```
serve [workspace] [--port <n>]    # default: directory corrente, porta 8080
```

Avvia un server in ascolto solo su `localhost`. La UI mostra l'albero di `<workspace>/features`, apre una feature in un editor con evidenziazione della sintassi Gherkin e la salva su disco; si possono creare, rinominare ed eliminare file e cartelle. I file restano la fonte di verità: nessuna integrazione con git.

- Se il file è cambiato su disco dopo l'apertura, il salvataggio è rifiutato (HTTP 409, confronto con un hash SHA-256 del contenuto letto) e la UI offre di ricaricare da disco o sovrascrivere.
- Le API rifiutano (HTTP 400) i percorsi che escono da `features`: `..`, percorsi assoluti e link simbolici verso l'esterno.
- API: `GET /api/tree`, `GET|PUT|POST /api/file?path=`, `POST /api/folder?path=`, `POST /api/rename` (`{from, to}`), `DELETE /api/entry?path=`; i percorsi sono relativi a `features`.
- Scelta sul frontend: JavaScript vanilla (nessun node, nessun download) in `server/src/main/resources/static`, con un evidenziatore Gherkin scritto a mano sovrapposto a una `textarea`. La "build" del frontend è lo step standard di Maven resources, che impacchetta la UI nel jar; `mvn test` funziona offline.

## Sorgenti e profili

Le feature citano le sorgenti con un nome logico. Indirizzi e credenziali stanno in `<workspace>/sources.yaml`, organizzato per profili; il profilo si sceglie al lancio con `--profile`.

```yaml
profiles:
  local:
    sources:
      ordini:
        type: postgresql        # il tipo di sorgente di un connettore installato
        config:                 # i parametri dichiarati dal connettore
          url: jdbc:postgresql://localhost:5432/ordini
          password: ${DB_PASSWORD}
  staging:
    sources:
      ordini:
        type: postgresql
        readOnly: true          # gli step che scrivono falliscono senza toccare i dati
        config:
          url: jdbc:postgresql://staging:5432/ordini
          password: ${DB_PASSWORD}
```

I valori `${VARIABILE}` di `config` sono risolti dalle variabili d'ambiente; servono solo quelle del profilo scelto. Una configurazione invalida, o un tipo di sorgente senza connettore installato, ferma `run` prima di eseguire qualsiasi scenario.

## Verifiche

Le frasi di verifica sono le stesse per ogni sorgente: il connettore restituisce i record osservati come JSON, il motore li filtra e li confronta.

Esistenza, non esistenza e conteggio (esattamente, almeno, al massimo N) contano i record che soddisfano un filtro, scritto come tabella `field | operator | value` (o `campo | operatore | valore`). Un record deve soddisfare tutte le righe; senza tabella contano tutti i record.

```gherkin
Then exactly 2 records exist in the fake store "ordini"
  | field           | operator     | value                |
  | status          | equals       | PAID                 |
  | $.customer.name | regex        | ^A                   |
  | total           | greater than | 100                  |
  | createdAt       | less than    | 2024-03-10T08:00:00Z |
  | cancelledAt     | is null      |                      |
```

| Operatore | Simbolo | In italiano | Soddisfatto se il valore osservato |
| --- | --- | --- | --- |
| `equals` | `=` | `uguale` | è uguale al valore |
| `not equals` | `!=` | `diverso` | è diverso dal valore, o manca |
| `contains` | | `contiene` | contiene il valore: come parte del testo, o come elemento se è un array |
| `regex` | | | contiene una corrispondenza dell'espressione regolare |
| `greater than` | `>` | `maggiore` | è maggiore del valore |
| `less than` | `<` | `minore` | è minore del valore |
| `is null` | | `è nullo` | è nullo o manca |
| `is not null` | | `non è nullo` | è presente e non nullo |

- Il campo è un JSONPath; il prefisso `$.` si può omettere, quindi `status` e `customer.name` sono già percorsi. Un percorso con caratteri jolly, come `$.lines[*].sku`, legge l'elenco dei valori.
- I confronti seguono il tipo del valore osservato: i numeri come numeri (`30` è uguale a `30.0`, `9.5` è minore di `120`), le date ISO-8601 come istanti (`09:00+02:00` precede `08:30Z`; senza offset valgono come UTC), il resto come testo.
- Un operatore sconosciuto o una tabella malformata fanno fallire lo step con un errore che cita file e riga, es. `features/ordini.feature:12: unknown operator 'above'`.

Il confronto con un JSON atteso, scritto come doc string, è soddisfatto se almeno un record corrisponde. In modalità stretta il record deve avere gli stessi campi e valori; in modalità parziale bastano i campi del JSON atteso (gli array vanno comunque confrontati elemento per elemento). I campi da ignorare sono un elenco di JSONPath separati da virgole.

```gherkin
Then a record of the fake store "ordini" equals the JSON ignoring "id, createdAt, $.lines[*].id"
  """
  {"status": "PAID", "lines": [{"sku": "A1"}, {"sku": "B2"}]}
  """
```

### Attese

I sistemi sotto test sono asincroni: un dato può comparire qualche secondo dopo l'azione che lo produce. Ogni verifica rilegge la sorgente e riprova finché non è soddisfatta o scade il timeout, senza attese scritte nella feature; allo scadere fallisce con i dati dell'ultima lettura.

Le verifiche che un dato in arrivo può solo smentire, cioè la non esistenza e "al massimo N", si comportano al contrario: attendono l'intera finestra e falliscono appena il dato compare.

Timeout e intervallo di polling di default sono 10 secondi e 500 millisecondi. Si cambiano in `<workspace>/settings.yaml`, con durate in `s` o `ms`; `timeout: 0ms` fa un solo tentativo.

```yaml
verification:
  timeout: 30s
  pollInterval: 200ms
```

Una frase può indicare un timeout diverso, in secondi, e per i rari casi in cui il polling non basta esiste un'attesa esplicita (`I wait N seconds`, `attendo N secondi`), disponibile con qualsiasi connettore:

```gherkin
When I wait 2 seconds
Then a record exists in the fake store "ordini" within 30 seconds
  | field  | operator | value |
  | status | equals   | PAID  |
And no record exists in the fake store "ordini" for 5 seconds
  | field  | operator | value     |
  | status | equals   | CANCELLED |
```

Le frasi qui sopra sono quelle del connettore finto. Ogni connettore dichiara le proprie, indicando per ciascuna l'azione di lettura, la verifica che il motore applica ai record restituiti e, se vuole, il parametro `{timeout}`.

## Diagnostica dei fallimenti

Una verifica fallita riporta la condizione attesa e i record osservati all'ultimo tentativo, così l'autore capisce il problema senza aprire il database:

```
Expected exactly 2 matching records, but 1 of the 3 observed records match
Observed records:
  {"id":1,"status":"PAID"}
  {"id":2,"status":"NEW"}
  (1 more record omitted)
```

Per non esporre dati riservati il motore, per tutti i connettori:

- tronca l'output a `diagnostics.maxRecords` record (default 10) e a `diagnostics.maxSize` caratteri di JSON (default 2000), in `<workspace>/settings.yaml`; un record si mostra intero o non si mostra, e quelli omessi sono contati;
- maschera con `***` i campi elencati in `mask` nella configurazione di una sorgente, in `sources.yaml`. Sono JSONPath, quindi anche annidati o dentro array; `$..password` li maschera a qualsiasi profondità.

```yaml
# settings.yaml
diagnostics:
  maxRecords: 5
  maxSize: 1000
```

```yaml
# sources.yaml
profiles:
  local:
    sources:
      ordini:
        type: postgresql
        mask: [password, customer.card, $.lines[*].secret]
        config: ...
```

Il mascheramento riguarda ciò che si mostra: i filtri e i confronti JSON vedono i valori reali.

## Variabili e generatori

Un valore noto solo durante l'esecuzione, come un id generato dal sistema, si salva in una variabile e si riusa come `${nome}` nel testo degli step, nelle tabelle e nei testi multilinea. Le variabili valgono solo nello scenario che le definisce; citarne una non definita fa fallire lo step con un errore che la nomina.

```gherkin
When I save the "id" of a record of the fake store "ordini" as "orderId"
  | field  | operator | value |
  | status | equals   | NEW   |
Then a record exists in the fake store "pagamenti" within 30 seconds
  | field   | operator | value      |
  | orderId | equals   | ${orderId} |
```

La frase di salvataggio legge il campo (un JSONPath) del primo record che soddisfa il filtro, e riprova come le altre verifiche finché il record c'è. In italiano: `salvo "id" di un record dello store finto "ordini" come "orderId"`. Anche i parametri numerici accettano una variabile (`exactly ${n} records exist ...`).

| Riferimento | Valore |
| --- | --- |
| `${nome}` | la variabile dello scenario |
| `${env:NOME}` | la variabile d'ambiente, con sintassi distinta dalle variabili di scenario |
| `${uuid:nome}` | un UUID |
| `${now:nome}`, `${now+1d:nome}` | l'istante corrente (ISO-8601), con offset opzionale in `ms`, `s`, `m`, `h`, `d` (`+1d`, `-2h`) |
| `${random:nome}` | un numero casuale intero non negativo |

Un generatore ha un nome: riusato con lo stesso nome nello scenario dà lo stesso valore (che è anche la variabile `${nome}`), mentre un altro scenario ne ottiene uno diverso.

## Catalogo delle frasi

Le frasi sono dati. Il catalogo effettivo è l'unione delle frasi di default dei connettori (ognuna in italiano e in inglese, che portano alla stessa azione), di quelle del motore e degli alias del workspace. Una feature con `# language: it` usa le keyword Gherkin italiane e le frasi italiane:

```gherkin
# language: it
Funzionalità: Store
  Scenario: Contatori
    Dato lo store finto "s" ha "k" impostato a 3
    Allora lo store finto "s" deve avere "k" uguale a 3
```

Gli alias, cioè nuove formulazioni di una frase esistente, stanno in `<workspace>/catalog.yaml`, caricato all'avvio:

```yaml
aliases:
  - phrase: "{key} of the fake store {source} is {value}"   # la nuova formulazione
    for: "the fake store {source} has {key} set to {value}"  # la frase di default che rappresenta
    language: en                                             # facoltativo, en di default
```

Un alias deve usare gli stessi parametri della frase che rappresenta, altrimenti il caricamento fallisce con un errore che li elenca. Due frasi i cui pattern possono corrispondere allo stesso step sono segnalate come ambigue, indicando entrambe e la loro origine, prima di eseguire qualsiasi scenario. `Engine.catalog(workspace)` restituisce il catalogo effettivo: per ogni frase pattern, lingua, origine (`connector:<tipo>`, `engine`, `workspace`), bersaglio (`<tipo>.<azione>`), verifica e parametri.

## Selezione

Senza opzioni `run` esegue tutte le feature del workspace. Le opzioni restringono la selezione e si combinano (uno scenario gira se è sotto uno dei percorsi **e** soddisfa l'espressione di tag):

| Opzione | Effetto |
| --- | --- |
| `--path <percorso>` | una cartella, un file o un singolo scenario; ripetibile |
| `--tags <espressione>` | espressione di tag Cucumber |

I percorsi sono relativi al workspace e devono stare in `features/`; `file:riga` seleziona lo scenario che contiene quella riga (la riga dello scenario o di uno dei suoi step).

```
run ws --profile local                                        # tutto il workspace
run ws --profile local --path features/ordini                 # una cartella
run ws --profile local --path features/ordini/pagamenti.feature   # un file
run ws --profile local --path features/ordini/pagamenti.feature:12  # uno scenario
run ws --profile local --tags "@smoke and not @wip"           # per tag
```

Le espressioni di tag usano `and`, `or`, `not` e le parentesi, es. `@smoke`, `not @wip`, `@a and (@b or @c)`. Un percorso inesistente o un'espressione non valida fermano `run` con un errore prima di eseguire qualsiasi scenario.

Una selezione che non corrisponde a nessuno scenario non è un successo: `run` lo scrive su stderr (`No scenario matches the selection: nothing was executed`) ed esce con codice 3. In `ExecutionResult`, `noScenarioSelected()` è vero e `successful()` falso.

Codici di uscita di `run`: `0` tutti gli scenari passati, `1` almeno uno non passato, `2` errore di uso o di configurazione, `3` nessuno scenario selezionato.

## Report

Ogni esecuzione scrive in `<workspace>/reports/<istante>/`, dove `<istante>` è l'istante di avvio in UTC nel formato `yyyyMMdd-HHmmss-SSS` (sicuro su ogni filesystem; un eventuale suffisso `-2`, `-3`... distingue esecuzioni nello stesso millisecondo):

- `cucumber.json`: JSON Cucumber;
- `junit.xml`: JUnit XML, per gli strumenti di CI;
- `report.html`: report HTML;
- `summary.json`: riepilogo leggibile da macchina.

```json
{
  "profile": "local",
  "selection": { "paths": ["features/ordini"], "tags": "@smoke" },
  "startedAt": "2026-10-13T16:11:11.885Z",
  "durationMillis": 412,
  "counts": { "total": 3, "passed": 2, "failed": 1, "skipped": 0, "undefined": 0 },
  "noScenarioSelected": false,
  "successful": false
}
```

`run` stampa in coda il percorso della cartella (`Reports: ...`); `ExecutionResult.reports()` lo espone al codice che usa il motore.

Si conservano le ultime 20 esecuzioni; il numero si cambia in `<workspace>/settings.yaml` (intero maggiore o uguale a 1). Le cartelle più vecchie vengono eliminate al termine di ogni esecuzione; solo quelle con un nome nel formato sopra, quindi qualunque altro file o cartella in `reports/` non viene toccato.

```yaml
reports:
  keep: 50
```

## Connettore MongoDB

Tipo di sorgente `mongodb`. Esempio completo, in italiano e in inglese: `examples/mongodb` (è anche il test del connettore, che richiede Docker).

```yaml
profiles:
  local:
    sources:
      orders-db:
        type: mongodb
        config:
          image: mongo:7           # container avviato dallo strumento; in alternativa:
          # uri: ${MONGODB_URI}    # un MongoDB già in esecuzione
          database: shop
```

Con `image` il container parte al primo uso della sorgente e viene fermato alla fine del processo: in `run` a fine esecuzione, in `serve` resta attivo finché il server è acceso. Serve Docker.

Frasi (qui in inglese; ogni frase ha la sua versione italiana):

| Frase | Effetto |
| --- | --- |
| `the MongoDB collection "c" of source "s" is emptied` | svuota la collection |
| `the documents below are inserted in the MongoDB collection "c" of source "s"` + testo multilinea | inserisce un documento o un array di documenti |
| `the documents of the file "data/orders.json" are inserted in ...` | come sopra, da un file del workspace |
| `a document exists in the MongoDB collection "c" of source "s"` (+ tabella) | esistenza; anche `no document exists`, `exactly/at least/at most N documents exist`, `... within N second(s)` |
| `... matching the filter '{"status": "PAID"}'` | gli stessi controlli con un filtro MongoDB nativo al posto della tabella |
| `a document of the MongoDB collection ... equals / contains the JSON` | confronto JSON, anche con `ignoring` |
| `I save the "field" of a document of the MongoDB collection ... as "var"` | cattura una variabile; anche con `matching the filter` |

Documenti e filtri sono in MongoDB Extended JSON: `{"$oid": "..."}` per un ObjectId, `{"$date": "2024-03-01T10:00:00Z"}` per una data, `{"$numberDecimal": "19.99"}` per un decimale.

I documenti osservati hanno una forma JSON stabile: ObjectId come testo di 24 cifre esadecimali; date come testo UTC con millisecondi (`2024-03-01T10:00:00.000Z`); Decimal128 come numero con il suo valore esatto (`19.99`); Binary di tipo UUID come testo dell'UUID, gli altri Binary in Base64; ogni altro tipo non numerico come testo.

Le frasi che scrivono sulla sorgente (svuotare, inserire) con `readOnly: true` falliscono senza toccare i dati.

## Build

Richiede Java 21 e Maven.

```
mvn test
```
