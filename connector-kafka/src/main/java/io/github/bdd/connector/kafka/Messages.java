package io.github.bdd.connector.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Reads and writes the topics of a broker. Nothing is kept between calls, so each shows the broker as it is. */
final class Messages {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Configuration PATHS = Configuration.builder().options(Option.SUPPRESS_EXCEPTIONS).build();
    private static final Duration BROKER_TIMEOUT = Duration.ofSeconds(15);

    /**
     * A message that arrived.
     *
     * @param record the message as a whole: topic, partition, offset, timestamp, key, headers and value
     * @param value the payload: a JSON structure if it parses as one, its text otherwise, null if it has none
     */
    record Received(Map<String, Object> record, Object value) {
    }

    /** Sends a message and waits until the broker has it. */
    void publish(String bootstrapServers, String topic, String key, Map<String, String> headers, String payload)
            throws Exception {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, (int) BROKER_TIMEOUT.toMillis());
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) BROKER_TIMEOUT.toMillis());
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) BROKER_TIMEOUT.toMillis() * 2);

        List<Header> kafkaHeaders = new ArrayList<>();
        headers.forEach((name, value) ->
                kafkaHeaders.add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8))));
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(properties)) {
            producer.send(new ProducerRecord<>(topic, null, null,
                    key == null ? null : key.getBytes(StandardCharsets.UTF_8),
                    payload.getBytes(StandardCharsets.UTF_8), kafkaHeaders))
                    .get(BROKER_TIMEOUT.toSeconds() * 2, TimeUnit.SECONDS);
        }
    }

    /**
     * The messages of a topic stamped at or after an instant, in arrival order: by timestamp, then
     * by partition and offset. A topic that does not exist yet has none.
     */
    List<Received> since(String bootstrapServers, String topic, Instant since) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // Reading must never create the topic that is not there yet.
        properties.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);

        List<ConsumerRecord<byte[], byte[]>> consumed = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(properties)) {
            List<PartitionInfo> infos = consumer.partitionsFor(topic, BROKER_TIMEOUT);
            if (infos == null || infos.isEmpty()) {
                return List.of();
            }
            List<TopicPartition> partitions = infos.stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .toList();
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions, BROKER_TIMEOUT);
            Map<TopicPartition, Long> times = new HashMap<>();
            partitions.forEach(partition -> times.put(partition, since.toEpochMilli()));
            Map<TopicPartition, OffsetAndTimestamp> starts = consumer.offsetsForTimes(times, BROKER_TIMEOUT);

            consumer.assign(partitions);
            List<TopicPartition> pending = new ArrayList<>();
            for (TopicPartition partition : partitions) {
                OffsetAndTimestamp start = starts.get(partition);
                if (start != null && start.offset() < ends.get(partition)) {
                    consumer.seek(partition, start.offset());
                    pending.add(partition);
                }
            }
            long deadline = System.nanoTime() + BROKER_TIMEOUT.toNanos() * 2;
            while (!pending.isEmpty()) {
                consumer.poll(Duration.ofMillis(200)).forEach(consumed::add);
                pending.removeIf(partition -> consumer.position(partition) >= ends.get(partition));
                if (System.nanoTime() - deadline > 0) {
                    throw new IllegalStateException("Timed out reading the topic '" + topic + "'");
                }
            }
        }
        return consumed.stream()
                .filter(record -> record.timestamp() >= since.toEpochMilli())
                .sorted(Comparator.<ConsumerRecord<byte[], byte[]>>comparingLong(ConsumerRecord::timestamp)
                        .thenComparingInt(ConsumerRecord::partition)
                        .thenComparingLong(ConsumerRecord::offset))
                .map(Messages::received)
                .toList();
    }

    private static Received received(ConsumerRecord<byte[], byte[]> record) {
        Object value = record.value() == null ? null : payload(new String(record.value(), StandardCharsets.UTF_8));
        Map<String, Object> headers = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            headers.put(header.key(),
                    header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8));
        }
        Map<String, Object> whole = new LinkedHashMap<>();
        whole.put("topic", record.topic());
        whole.put("partition", record.partition());
        whole.put("offset", record.offset());
        whole.put("timestamp", Instant.ofEpochMilli(record.timestamp()).toString());
        whole.put("key", record.key() == null ? null : new String(record.key(), StandardCharsets.UTF_8));
        whole.put("headers", headers);
        whole.put("value", value);
        return new Received(whole, value);
    }

    /** The JSON the text is, or the text itself. */
    private static Object payload(String text) {
        try {
            return JSON.readValue(text, Object.class);
        } catch (JsonProcessingException e) {
            return text;
        }
    }

    /**
     * The headers of a step: a JSON object, or <code>name=value</code> pairs separated by commas.
     *
     * @throws IllegalArgumentException if it is neither
     */
    static Map<String, String> headers(String text) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (text.strip().startsWith("{")) {
            if (!(payload(text) instanceof Map<?, ?> object)) {
                throw new IllegalArgumentException("The headers '" + text + "' are not a JSON object");
            }
            object.forEach((name, value) -> headers.put(name.toString(), String.valueOf(value)));
            return headers;
        }
        for (String pair : list(text)) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException("The header '" + pair
                        + "' is not written as name=value; headers are 'name=value' pairs separated by commas, "
                        + "or a JSON object");
            }
            headers.put(pair.substring(0, equals).strip(), pair.substring(equals + 1).strip());
        }
        return headers;
    }

    /** The comma-separated items of a text, without surrounding blanks. */
    static List<String> list(String text) {
        return List.of(text.split(",")).stream().map(String::strip).filter(item -> !item.isEmpty()).toList();
    }

    /** The value at a JSONPath of a payload, as text; null if there is none. */
    static String text(Object payload, String path) {
        Object value = JsonPath.compile(path.strip()).read(payload, PATHS);
        if (value == null) {
            return null;
        }
        return value instanceof Map || value instanceof List ? payloadText(value) : value.toString();
    }

    private static String payloadText(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
