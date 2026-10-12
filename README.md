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

## Build

Richiede Java 21 e Maven.

```
mvn test
```
