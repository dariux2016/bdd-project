# language: it
Funzionalità: Ordini su Kafka

  Scenario: Un messaggio pubblicato è verificato sui campi del payload
    Quando il messaggio '{"id": "${uuid:id}", "stato": "NUOVO"}' con la chiave "cliente-1" viene pubblicato sul topic "ordini" della sorgente Kafka "events"
    Allora esiste un messaggio sul topic "ordini" della sorgente Kafka "events"
      | campo | operatore | valore |
      | id    | uguale    | ${id}  |
    E esiste un record Kafka sul topic "ordini" della sorgente Kafka "events" entro 5 secondi
      | campo | operatore | valore    |
      | key   | uguale    | cliente-1 |
    E esistono esattamente 1 messaggi sul topic "ordini" della sorgente Kafka "events"
      | campo | operatore | valore |
      | id    | uguale    | ${id}  |
