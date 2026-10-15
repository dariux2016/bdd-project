# language: it
Funzionalità: API degli ordini

  Scenario: Un ordine viene creato e poi pagato in modo asincrono
    Dato le richieste al servizio HTTP "orders" hanno gli header
      | header           | valore              |
      | X-Correlation-Id | ${uuid:correlazione} |
    Quando invio una richiesta POST a "/orders" sul servizio HTTP "orders"
      """
      {"customer": "Ada", "lines": [{"sku": "A1", "quantity": 2}]}
      """
    Allora la risposta del servizio HTTP "orders" ha lo status 201
    E la risposta del servizio HTTP "orders" corrisponde
      | campo            | operatore | valore            |
      | headers.location | regex     | ^/api/orders/\d+$ |
      | body.status      | uguale    | NEW               |
    E il corpo della risposta del servizio HTTP "orders" è uguale al JSON ignorando "id, createdAt"
      """
      {"customer": "Ada", "status": "NEW", "lines": [{"sku": "A1", "quantity": 2}]}
      """
    Quando salvo "body.id" della risposta del servizio HTTP "orders" come "idOrdine"
    E invio richieste GET a "/orders/${idOrdine}" sul servizio HTTP "orders" finché la risposta corrisponde entro 5 secondi
      | campo       | operatore | valore |
      | status      | uguale    | 200    |
      | body.status | uguale    | PAID   |
    Allora il corpo della risposta del servizio HTTP "orders" contiene il JSON
      """
      {"id": ${idOrdine}, "status": "PAID"}
      """

  Scenario: Un ordine inesistente
    Quando invio una richiesta DELETE a "/orders/999" sul servizio HTTP "orders"
    Allora la risposta del servizio HTTP "orders" ha lo status 404
