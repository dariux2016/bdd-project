Feature: Orders and customers in PostgreSQL

  Background:
    Given the script file "scripts/schema.sql" is executed on the PostgreSQL source "shop"
    And the table "orders" of the PostgreSQL source "shop" is emptied
    And the table "customers" of the PostgreSQL source "shop" is emptied

  @smoke
  Scenario: Rows from a table are found with the unified filter
    Given these rows are inserted in table "customers" of the PostgreSQL source "shop"
      | id | name | vip   | profile                              |
      | 1  | Ada  | true  | {"tier": "gold", "tags": ["a", "b"]} |
      | 2  | Bob  | false |                                      |
    Then a row exists in table "customers" of the PostgreSQL source "shop"
      | field        | operator | value |
      | name         | equals   | Ada   |
      | vip          | equals   | true  |
      | profile.tier | equals   | gold  |
    And exactly 2 rows exist in table "customers" of the PostgreSQL source "shop"
    And at least 1 row exists in table "customers" of the PostgreSQL source "shop"
      | field   | operator | value |
      | profile | is null  |       |
    And no row exists in table "customers" of the PostgreSQL source "shop"
      | field | operator | value |
      | name  | equals   | Zed   |

  Scenario: Column types are reported as JSON types
    Given these rows are inserted in table "customers" of the PostgreSQL source "shop"
      | id | name |
      | 1  | Ada  |
    And these rows are inserted in table "orders" of the PostgreSQL source "shop"
      | customer_id | status | total | placed_at            | shipped_on |
      | 1           | PAID   | 10.50 | 2024-03-10T08:00:00Z | 2024-03-12 |
    Then a row of table "orders" of the PostgreSQL source "shop" equals the JSON ignoring "id"
      """
      {
        "customer_id": 1,
        "status": "PAID",
        "total": 10.5,
        "placed_at": "2024-03-10T08:00:00Z",
        "shipped_on": "2024-03-12"
      }
      """
    And a row exists in table "orders" of the PostgreSQL source "shop"
      | field     | operator     | value                |
      | total     | greater than | 9.99                 |
      | placed_at | less than    | 2024-03-10T09:00:00Z |

  Scenario: Rows from files
    Given these rows are inserted in table "customers" of the PostgreSQL source "shop"
      | id | name |
      | 1  | Ada  |
    When the rows of the file "data/customers.json" are inserted in table "customers" of the PostgreSQL source "shop"
    And the rows of the file "data/orders.csv" are inserted in table "orders" of the PostgreSQL source "shop"
    Then exactly 3 rows exist in table "customers" of the PostgreSQL source "shop"
    And a row of table "customers" of the PostgreSQL source "shop" contains the JSON
      """
      {"name": "Grace", "profile": {"tags": ["early"]}}
      """
    And exactly 1 row exists in table "orders" of the PostgreSQL source "shop"
      | field      | operator | value |
      | shipped_on | is null  |       |

  Scenario: Native queries verify and capture values
    Given the script is executed on the PostgreSQL source "shop"
      """
      INSERT INTO customers (id, name) VALUES (1, 'Ada'), (2, 'Bob');
      INSERT INTO orders (customer_id, status, total, placed_at)
      VALUES (1, 'PAID', 20, now()), (1, 'PAID', 30, now()), (2, 'NEW', 5, now());
      """
    Then the query "SELECT status, count(*) AS n FROM orders GROUP BY status" on the PostgreSQL source "shop" returns exactly 2 rows
    And the query "SELECT sum(total) AS amount FROM orders WHERE status = 'PAID'" on the PostgreSQL source "shop" returns a row
      | field  | operator | value |
      | amount | equals   | 50    |
    When I save the "name" of a row returned by the query "SELECT c.name FROM customers c JOIN orders o ON o.customer_id = c.id WHERE o.status = 'NEW'" on the PostgreSQL source "shop" as "buyer"
    Then a row exists in table "customers" of the PostgreSQL source "shop"
      | field | operator | value    |
      | name  | equals   | ${buyer} |
