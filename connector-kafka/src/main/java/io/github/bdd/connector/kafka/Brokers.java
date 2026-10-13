package io.github.bdd.connector.kafka;

import io.github.bdd.plugin.Source;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.Map;

/**
 * Where the broker of a source is. A source with an {@code image} is a container started on first
 * use and shared by every execution of the JVM: it stays up while a server runs several, and goes
 * away with the process, which is when a headless run ends.
 */
final class Brokers {

    static final String BOOTSTRAP_SERVERS = "bootstrapServers";
    static final String IMAGE = "image";

    private static final Map<String, KafkaContainer> CONTAINERS = new HashMap<>();

    private Brokers() {
    }

    /** @throws IllegalStateException if the source declares neither an address nor an image, or both */
    static synchronized String bootstrapServers(Source source) {
        Object address = source.config().get(BOOTSTRAP_SERVERS);
        Object image = source.config().get(IMAGE);
        if (address != null && image != null) {
            throw new IllegalStateException("Source '" + source.name() + "' sets both " + BOOTSTRAP_SERVERS
                    + " and " + IMAGE + ": use the address of a running broker, or an image to start one");
        }
        if (address != null) {
            return (String) address;
        }
        if (image == null) {
            throw new IllegalStateException("Source '" + source.name() + "' sets neither " + BOOTSTRAP_SERVERS
                    + " nor " + IMAGE + ", so there is no broker to act on");
        }
        return CONTAINERS.computeIfAbsent((String) image, Brokers::start).getBootstrapServers();
    }

    private static KafkaContainer start(String image) {
        KafkaContainer container = new KafkaContainer(DockerImageName.parse(image));
        container.start();
        Runtime.getRuntime().addShutdownHook(new Thread(container::stop));
        return container;
    }
}
