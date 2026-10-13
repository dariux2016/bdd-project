package io.github.bdd.cli;

import io.github.bdd.server.FeatureServer;

import java.io.PrintStream;
import java.nio.file.Path;

/** The {@code serve [workspace] [--port <n>]} command. */
final class Serve {

    static final String USAGE = "Usage: serve [workspace] [--port <n>]";
    static final int DEFAULT_PORT = 8080;
    private static final int EXIT_ERROR = 2;

    private Serve() {
    }

    /**
     * Parses the arguments (args[0] is {@code serve}) and starts the server. Returns an exit
     * code on failure, or -1 when the server is running and the process must stay alive.
     */
    static int execute(String[] args, PrintStream out, PrintStream err) {
        String workspace = null;
        int port = DEFAULT_PORT;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                try {
                    port = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    err.println(USAGE);
                    return EXIT_ERROR;
                }
                if (port < 0 || port > 65535) {
                    err.println(USAGE);
                    return EXIT_ERROR;
                }
            } else if (!args[i].startsWith("--") && workspace == null) {
                workspace = args[i];
            } else {
                err.println(USAGE);
                return EXIT_ERROR;
            }
        }
        Path path = Path.of(workspace == null ? "" : workspace);
        try {
            FeatureServer server = new FeatureServer(path, port).start();
            out.println("Serving " + path.toAbsolutePath().normalize() + " on http://localhost:" + server.port());
            return -1;
        } catch (Exception e) {
            err.println("Cannot start the server: " + e.getMessage());
            return EXIT_ERROR;
        }
    }
}
