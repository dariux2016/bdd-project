package io.github.bdd.engine;

import io.github.bdd.plugin.Connector;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.jar.JarFile;

/**
 * The installed connectors: those on the class path of the tool, and those of the plugins in the
 * {@code plugins} folder of the workspace, discovered with {@link ServiceLoader}.
 *
 * <p>A plugin is a jar directly in {@code plugins/}, or a directory there whose jars, the connector
 * and its dependencies, are loaded together. Each plugin has a class loader of its own, which sees
 * the platform, the contract of the connectors ({@code io.github.bdd.plugin}) and its own jars,
 * nothing else of the tool or of the other plugins: its dependencies never conflict with theirs.
 *
 * <p>Closing releases the jars of the plugins, once their connectors are no longer used.
 */
final class Connectors implements AutoCloseable {

    static final String DIRECTORY = "plugins";

    private static final String CONTRACT = Connector.class.getPackageName() + ".";

    private final List<Connector> connectors = new ArrayList<>();
    /** Where each connector comes from, for the errors that name it. */
    private final Map<Connector, String> origins = new IdentityHashMap<>();
    private final List<URLClassLoader> loaders = new ArrayList<>();

    private Connectors() {
    }

    /**
     * @throws ConfigurationException if a plugin cannot be loaded or contains no connector, or two
     *     connectors handle the same source type
     */
    static Connectors load(Path workspace, ClassLoader tool) {
        Connectors installed = new Connectors();
        try {
            installed.add(ServiceLoader.load(Connector.class, tool), "the class path of the tool");
            for (Path plugin : plugins(workspace.resolve(DIRECTORY))) {
                installed.addPlugin(workspace, plugin, tool);
            }
            installed.rejectSameSourceType();
            return installed;
        } catch (RuntimeException | Error e) {
            installed.close();
            throw e;
        }
    }

    List<Connector> list() {
        return List.copyOf(connectors);
    }

    /** The jars directly in the folder, and its directories, in the order of their names. */
    private static List<Path> plugins(Path folder) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        List<Path> plugins = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(folder)) {
            for (Path child : children) {
                if (Files.isDirectory(child) || isJar(child)) {
                    plugins.add(child);
                }
            }
        } catch (IOException e) {
            throw new ConfigurationException("Cannot list the plugins in " + folder + ": " + e.getMessage());
        }
        plugins.sort(null);
        return plugins;
    }

    private static boolean isJar(Path file) {
        return Files.isRegularFile(file) && file.getFileName().toString().toLowerCase().endsWith(".jar");
    }

    private void addPlugin(Path workspace, Path plugin, ClassLoader tool) {
        String name = workspace.relativize(plugin).toString().replace('\\', '/');
        List<Path> jars = new ArrayList<>();
        if (Files.isDirectory(plugin)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(plugin)) {
                children.forEach(child -> {
                    if (isJar(child)) {
                        jars.add(child);
                    }
                });
            } catch (IOException e) {
                throw new ConfigurationException("Cannot list the jars of plugin '" + name + "': " + e.getMessage());
            }
            jars.sort(null);
        } else {
            jars.add(plugin);
        }

        List<URL> urls = new ArrayList<>();
        for (Path jar : jars) {
            // A class loader skips a jar it cannot read: it is checked here, so that it is named.
            try (JarFile ignored = new JarFile(jar.toFile())) {
                urls.add(jar.toUri().toURL());
            } catch (MalformedURLException e) {
                throw new IllegalStateException(e);
            } catch (IOException e) {
                throw new ConfigurationException("Plugin '" + name + "' cannot be loaded: '"
                        + workspace.relativize(jar).toString().replace('\\', '/') + "' is not a readable jar ("
                        + e.getMessage() + ")");
            }
        }
        PluginClassLoader loader = new PluginClassLoader(name, urls.toArray(URL[]::new), tool);
        loaders.add(loader);
        int before = connectors.size();
        add(ServiceLoader.load(Connector.class, loader), "plugin '" + name + "'");
        if (connectors.size() == before) {
            throw new ConfigurationException("Plugin '" + name + "' contains no connector: a connector is declared in "
                    + "META-INF/services/" + Connector.class.getName() + " of its jar");
        }
    }

    private void add(ServiceLoader<Connector> loader, String origin) {
        try {
            for (Connector connector : loader) {
                // A dependency the connector lacks may only show when it is first used.
                connector.sourceType();
                connector.actions();
                connector.defaultPhrases();
                connectors.add(connector);
                origins.put(connector, origin);
            }
        } catch (ServiceConfigurationError | LinkageError e) {
            throw new ConfigurationException("The connectors of " + origin + " cannot be loaded: " + e.getMessage());
        }
    }

    private void rejectSameSourceType() {
        Map<String, Connector> byType = new HashMap<>();
        for (Connector connector : connectors) {
            Connector first = byType.putIfAbsent(connector.sourceType(), connector);
            if (first != null) {
                throw new ConfigurationException("Two connectors handle the source type '" + connector.sourceType()
                        + "': " + describe(first) + " and " + describe(connector));
            }
        }
    }

    private String describe(Connector connector) {
        return connector.getClass().getName() + " of " + origins.get(connector);
    }

    @Override
    public void close() {
        for (URLClassLoader loader : loaders) {
            try {
                loader.close();
            } catch (IOException e) {
                // The jars stay open until the class loader is collected: nothing else to do.
            }
        }
        loaders.clear();
    }

    /**
     * Loads the classes of a plugin from its jars first, so that its dependencies are its own, and
     * only the contract of the connectors from the tool, so that its connectors are those the engine
     * knows.
     */
    private static final class PluginClassLoader extends URLClassLoader {

        static {
            registerAsParallelCapable();
        }

        private final ClassLoader tool;

        PluginClassLoader(String name, URL[] urls, ClassLoader tool) {
            super(name, urls, ClassLoader.getPlatformClassLoader());
            this.tool = tool;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(CONTRACT)) {
                return tool.loadClass(name);
            }
            return super.loadClass(name, resolve);
        }
    }
}
