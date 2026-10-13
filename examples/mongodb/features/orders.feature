Feature: Orders in MongoDB

  Background:
    Given the MongoDB collection "orders" of source "orders-db" is emptied

  Scenario: Documents inserted from text are checked with the unified table
    Given the documents below are inserted in the MongoDB collection "orders" of source "orders-db"
      """
      [
        {"orderId": "A-1", "status": "PAID", "total": 120, "customer": {"name": "Alice"}, "lines": [{"sku": "X"}, {"sku": "Y"}]},
        {"orderId": "A-2", "status": "NEW", "total": 30, "customer": {"name": "Bob"}, "lines": [{"sku": "Z"}]}
      ]
      """
    Then exactly 1 document exists in the MongoDB collection "orders" of source "orders-db"
      | field         | operator     | value |
      | customer.name | equals       | Alice |
      | total         | greater than | 100   |
    And at least 2 documents exist in the MongoDB collection "orders" of source "orders-db"
    And a document exists in the MongoDB collection "orders" of source "orders-db"
      | field          | operator | value |
      | lines[1].sku   | equals   | Y     |

  Scenario: Documents inserted from a file
    Given the documents of the file "data/orders.json" are inserted in the MongoDB collection "orders" of source "orders-db"
    Then exactly 2 documents exist in the MongoDB collection "orders" of source "orders-db"
      | field  | operator | value |
      | status | equals   | PAID  |
    And a document of the MongoDB collection "orders" of source "orders-db" contains the JSON
      """
      {"orderId": "F-2", "total": 99}
      """

  Scenario: A native filter checks and captures a value
    Given the documents below are inserted in the MongoDB collection "orders" of source "orders-db"
      """
      {"orderId": "N-1", "status": "NEW"}
      """
    When I save the "orderId" of a document of the MongoDB collection "orders" of source "orders-db" matching the filter '{"status": {"$in": ["NEW", "PAID"]}}' as "found"
    Then a document exists in the MongoDB collection "orders" of source "orders-db" matching the filter '{"orderId": "${found}"}'
    And no document exists in the MongoDB collection "orders" of source "orders-db" matching the filter '{"status": "CANCELLED"}' for 1 second

  Scenario: ObjectIds, dates and decimals have a stable JSON form
    Given the documents below are inserted in the MongoDB collection "orders" of source "orders-db"
      """
      {
        "_id": {"$oid": "65f0c0ffee0000000000abcd"},
        "placedAt": {"$date": "2024-03-01T10:00:00Z"},
        "total": {"$numberDecimal": "19.99"}
      }
      """
    Then a document of the MongoDB collection "orders" of source "orders-db" equals the JSON
      """
      {"_id": "65f0c0ffee0000000000abcd", "placedAt": "2024-03-01T10:00:00.000Z", "total": 19.99}
      """
    And a document exists in the MongoDB collection "orders" of source "orders-db" matching the filter '{"_id": {"$oid": "65f0c0ffee0000000000abcd"}}'
