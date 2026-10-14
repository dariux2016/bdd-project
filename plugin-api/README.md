# plugin-api

Il contratto dei connettori: l'unica dipendenza di cui un connettore ha bisogno.

```xml
<dependency>
    <groupId>io.github.bdd</groupId>
    <artifactId>plugin-api</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
```

## Scrivere un connettore

1. Implementa `io.github.bdd.plugin.Connector`:
   - `sourceType()`: il tipo di sorgente, unico tra tutti i connettori installati (es. `redis`);
   - `configSchema()`: i parametri accettati in `sources.yaml`;
   - `actions()`: le azioni, con nome, `ActionKind` (`READ` o `WRITE`: il motore blocca le `WRITE` sulle sorgenti `readOnly`), parametri tipizzati e handler; l'handler restituisce i record osservati, mentre attese, confronti e mascheramento sono del motore;
   - `defaultPhrases()`: le frasi di default per lingua, ognuna legata a un'azione.
2. Dichiaralo in `META-INF/services/io.github.bdd.plugin.Connector` (il nome completo della classe, una riga per connettore).
3. Impacchetta un jar che contenga anche le proprie dipendenze (es. `maven-shade-plugin`); le classi di `io.github.bdd.plugin` non vanno incluse: il contratto è fornito dallo strumento.
4. Copia il jar in `<workspace>/plugins/`. Al lancio successivo il tipo di sorgente e le sue frasi sono disponibili.

Il codice di un connettore di esempio, compilato e impacchettato come jar separato, è in `engine/src/test/java/io/github/bdd/engine/PluginsTest.java`.
