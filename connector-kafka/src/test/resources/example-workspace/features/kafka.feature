@kafka
Feature: Orders on Kafka
  The system under test reads commands from one topic and writes its results to another. Only
  what arrives after a scenario starts is observed, so the topics can be shared.

  Scenario: A published message is observed by the fields of its payload
    When the message '{"id": "${uuid:id}", "status": "NEW", "total": 120}' is published to the topic "orders" of the Kafka source "events"
    Then a message exists on the topic "orders" of the Kafka source "events"
      | field  | operator | value |
      | id     | equals   | ${id} |
      | status | equals   | NEW   |
    And exactly 1 message exists on the topic "orders" of the Kafka source "events"
      | field | operator | value |
      | id    | equals   | ${id} |
    And no message exists on the topic "orders" of the Kafka source "events" for 1 second
      | field | operator | value |
      | id    | equals   | other |

  Scenario: The payload is compared with an expected JSON
    When the message '{"id": "${uuid:id}", "status": "NEW", "lines": [{"sku": "A1"}]}' is published to the topic "orders" of the Kafka source "events"
    Then a message on the topic "orders" of the Kafka source "events" contains the JSON
      """
      {"status": "NEW", "lines": [{"sku": "A1"}]}
      """
    And a message on the topic "orders" of the Kafka source "events" equals the JSON ignoring "id"
      """
      {"status": "NEW", "lines": [{"sku": "A1"}]}
      """

  Scenario: Key, headers and partition of a message are verified on the whole record
    When the message '{"id": "${uuid:id}"}' with the key "customer-7" and the headers "source=web, trace=abc" is published to the topic "orders" of the Kafka source "events"
    Then a Kafka record exists on the topic "orders" of the Kafka source "events"
      | field        | operator | value      |
      | value.id     | equals   | ${id}      |
      | key          | equals   | customer-7 |
      | headers.source | equals | web        |
      | headers.trace  | equals | abc        |
      | partition    | equals   | 0          |

  Scenario: Messages arrive in the expected relative order
    Given the message '{"seq": 1}' is published to the topic "steps-${uuid:run}" of the Kafka source "events"
    And the message '{"seq": 2}' is published to the topic "steps-${run}" of the Kafka source "events"
    And the message '{"seq": 3}' is published to the topic "steps-${run}" of the Kafka source "events"
    Then the messages on the topic "steps-${run}" of the Kafka source "events" arrive in the order of "$.seq": "1, 3"
    And exactly 3 messages exist on the topic "steps-${run}" of the Kafka source "events"

  Scenario: A field of a received message is kept for the next steps
    When the message '{"id": "${uuid:id}", "reference": "REF-${random:n}"}' is published to the topic "orders" of the Kafka source "events"
    And I save the "reference" of a message on the topic "orders" of the Kafka source "events" as "reference"
      | field | operator | value |
      | id    | equals   | ${id} |
    Then a message exists on the topic "orders" of the Kafka source "events"
      | field     | operator | value       |
      | reference | equals   | ${reference} |
