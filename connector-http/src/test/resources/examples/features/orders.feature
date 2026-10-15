Feature: Orders API

  Scenario: An order is created, then paid asynchronously
    Given the requests to the HTTP service "orders" have the headers
      | header           | value               |
      | X-Correlation-Id | ${uuid:correlation} |
    When I send a POST request to "/orders" on the HTTP service "orders"
      """
      {"customer": "Ada", "lines": [{"sku": "A1", "quantity": 2}]}
      """
    Then the response of the HTTP service "orders" has status 201
    And the response of the HTTP service "orders" matches
      | field            | operator | value             |
      | headers.location | regex    | ^/api/orders/\d+$ |
      | body.status      | equals   | NEW               |
    And the response body of the HTTP service "orders" equals the JSON ignoring "id, createdAt"
      """
      {"customer": "Ada", "status": "NEW", "lines": [{"sku": "A1", "quantity": 2}]}
      """
    When I save the "body.id" of the response of the HTTP service "orders" as "orderId"
    And I send GET requests to "/orders/${orderId}" on the HTTP service "orders" until the response matches within 5 seconds
      | field       | operator | value |
      | status      | equals   | 200   |
      | body.status | equals   | PAID  |
    Then the response body of the HTTP service "orders" contains the JSON
      """
      {"id": ${orderId}, "status": "PAID"}
      """

  Scenario: Searching with a query string
    When I send a GET request to "/orders?customer=Ada Lovelace&status=PAID" on the HTTP service "orders"
    Then the response of the HTTP service "orders" has status 200
    And the response of the HTTP service "orders" matches
      | field      | operator | value                               |
      | body.query | equals   | customer=Ada%20Lovelace&status=PAID |

  Scenario: A missing order
    When I send a DELETE request to "/orders/999" on the HTTP service "orders"
      | header   | value |
      | X-Reason | test  |
    Then the response of the HTTP service "orders" has status 404
