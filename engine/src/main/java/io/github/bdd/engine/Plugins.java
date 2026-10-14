package io.github.bdd.engine;

import io.github.bdd.plugin.Connector;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * The connectors available to an execution: those on the class path of the tool and those of the
 * jars in the {@code plugins} folder of the workspace.
 *
 * <p>Each jar gets a class loader of its own that sees the plugin API and the JDK, and nothing
 * else of the tool, so that the libraries of a plugin never clash with those of the tool or of
 * another plugin. A plugin therefore carries its own dependencies.
 */
final class Plugins implements AutoCloseable {

    static final String FOLDER = "plugins";

    private static final String PLUGIN_API_PACKAGE = "io.github.bdd.plugin.";

    private final List<Connector> connectors;
    private final List<URLClassLoader> loaders;

    private Plugins(List<Connector> connectors, List<URLClassLoader> loaders) {
        this.connectors = connectors;
        this.loaders = loaders;
    }

    /**
     * @throws ConfigurationException if a jar cannot be loaded, or two connectors handle the same
     *     source type
     */
    static Plugins load(Path workspace, ClassLoader classLoader) {
        Map<Connector, String> origins = new LinkedHashMap<>();
        List<URLClassLoader> loaders = new ArrayList<>();
        try {
            ServiceLoader.load(Connector.class, classLoader).stream()
                    .map(ServiceLoader.Provider::get)
                    .forEach(connector -> origins.put(connector, "the tool (" + connector.getClass().getName() + ")"));
            ClassLoader pluginParent = new PluginParent(classLoader);
            for (Path jar : jars(workspace.resolve(FOLDER))) {
                URLClassLoader loader = loader(jar, pluginParent);
                loaders.add(loader);
                for (Connector connector : connectors(jar, loader)) {
                    origins.put(connector, "plugin " + jar.getFileName() + " (" + connector.getClass().getName() + ")");
                }
            }
            reject(origins);
        } catch (RuntimeException failure) {
            close(loaders);
            throw failure;
        }
        return new Plugins(List.copyOf(origins.keySet()), loaders);
    }

    List<Connector> connectors() {
        return connectors;
    }

    @Override
    public void close() {
        close(loaders);
    }

    private static void close(List<URLClassLoader> loaders) {
        for (URLClassLoader loader : loaders) {
            try {
                loader.close();
            } catch (IOException ignored) {
                // nothing left to release
            }
        }
    }

    private static List<Path> jars(Path folder) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted()
                    .toList();
        } catch (IOException failure) {
            throw new ConfigurationException("Cannot read the plugins folder " + folder + ": " + failure);
        }
    }

    private static URLClassLoader loader(Path jar, ClassLoader parent) {
        try {
            // Opened eagerly so that a file that is not a jar is reported here, by name.
            new java.util.jar.JarFile(jar.toFile()).close();
            return new URLClassLoader(new URL[] {jar.toUri().toURL()}, parent);
        } catch (IOException failure) {
            throw new ConfigurationException("Cannot load plugin " + jar.getFileName() + ": " + failure);
        }
    }

    private static List<Connector> connectors(Path jar, ClassLoader loader) {
        try {
            return ServiceLoader.load(Connector.class, loader).stream()
                    .map(ServiceLoader.Provider::get)
                    .toList();
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError error) {
                throw error;
            }
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            throw new ConfigurationException("Cannot load plugin " + jar.getFileName() + ": " + cause);
        }
    }

    private static void reject(Map<Connector, String> origins) {
        Map<String, String> byType = new LinkedHashMap<>();
        origins.forEach((connector, origin) -> {
            String previous = byType.putIfAbsent(connector.sourceType(), origin);
            if (previous != null) {
                throw new ConfigurationException("Two connectors handle the source type '"
                        + connector.sourceType() + "': " + previous + " and " + origin);
            }
        });
    }

    /** Lets a plugin see the plugin API of the tool, and the JDK, and nothing else. */
    private static final class PluginParent extends ClassLoader {

        private final ClassLoader tool;

        PluginParent(ClassLoader tool) {
            super(ClassLoader.getPlatformClassLoader());
            this.tool = tool;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(PLUGIN_API_PACKAGE)) {
                return tool.loadClass(name);
            }
            return super.loadClass(name, resolve);
        }
    }
}
