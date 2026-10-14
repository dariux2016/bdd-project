# language: it
Funzionalità: Ordini su MongoDB

  Scenario: Gli stessi controlli in italiano
    Dato la collection MongoDB "orders" della sorgente "orders-db" è svuotata
    E i documenti qui sotto sono inseriti nella collection MongoDB "orders" della sorgente "orders-db"
      """
      [
        {"orderId": "I-1", "status": "PAGATO", "cliente": {"nome": "Anna"}},
        {"orderId": "I-2", "status": "NUOVO", "cliente": {"nome": "Bruno"}}
      ]
      """
    Allora esistono esattamente 1 documenti nella collection MongoDB "orders" della sorgente "orders-db"
      | campo        | operatore | valore |
      | cliente.nome | uguale    | Anna   |
    E esiste un documento nella collection MongoDB "orders" della sorgente "orders-db" corrispondente al filtro '{"status": "NUOVO"}'
    E non esiste alcun documento nella collection MongoDB "orders" della sorgente "orders-db" corrispondente al filtro '{"status": "ANNULLATO"}' per 1 secondo
    E salvo "orderId" di un documento della collection MongoDB "orders" della sorgente "orders-db" corrispondente al filtro '{"status": "PAGATO"}' come "pagato"
    E un documento della collection MongoDB "orders" della sorgente "orders-db" contiene il JSON
      """
      {"orderId": "${pagato}", "cliente": {"nome": "Anna"}}
      """
