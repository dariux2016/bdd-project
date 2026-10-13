# bdd-project

Strumento di test BDD black-box basato su Gherkin, riusabile su qualsiasi progetto: frasi in linguaggio naturale per verificare sorgenti dati configurabili (Kafka, PostgreSQL, MongoDB, HTTP) tramite connettori a plugin.

Stato: in costruzione. La specifica è nella issue #1.

## Moduli

- `plugin-api`: il contratto dei connettori.
- `engine`: esegue le feature di un workspace con le frasi dei connettori installati.
- `connector-fake`: connettore finto in memoria, supporto di test del motore.
- `cli`: punto d'ingresso; `run [workspace] [--profile <nome>]` esegue le feature in `<workspace>/features` sulle sorgenti del profilo scelto; `serve [workspace] [--port <n>]` avvia l'interfaccia web (vedi sotto).
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

## Build

Richiede Java 21 e Maven.

```
mvn test
```
