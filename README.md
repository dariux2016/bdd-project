# bdd-project

Strumento di test BDD black-box basato su Gherkin, riusabile su qualsiasi progetto: frasi in linguaggio naturale per verificare sorgenti dati configurabili (Kafka, PostgreSQL, MongoDB, HTTP) tramite connettori a plugin.

Stato: in costruzione. La specifica è nella issue #1.

## Moduli

- `plugin-api`: il contratto dei connettori.
- `engine`: esegue le feature di un workspace con le frasi dei connettori installati.
- `connector-fake`: connettore finto in memoria, supporto di test del motore.
- `cli`: punto d'ingresso; `run [workspace] [--profile <nome>]` esegue le feature in `<workspace>/features` sulle sorgenti del profilo scelto.

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

Le frasi qui sopra sono quelle del connettore finto. Ogni connettore dichiara le proprie, indicando per ciascuna l'azione di lettura e la verifica che il motore applica ai record restituiti.

## Build

Richiede Java 21 e Maven.

```
mvn test
```
