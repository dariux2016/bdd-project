package io.github.bdd.server;

import io.github.bdd.engine.Cancellation;
import io.github.bdd.engine.Engine;
import io.github.bdd.engine.ExecutionListener;
import io.github.bdd.engine.ExecutionRequest;
import io.github.bdd.engine.ExecutionResult;
import io.github.bdd.engine.ScenarioResult;
import io.github.bdd.engine.Selection;
import io.github.bdd.engine.Status;
import io.github.bdd.engine.StepResult;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * The executions of a workspace, one at a time: it runs the one asked for on its own thread and
 * keeps its events, so that whoever looks at it later, even while it runs, sees how it went.
 * The last execution stays available until the next one starts.
 */
final class Executions {

    private static final String FEATURES = "features/";

    /** An execution is running, so no other can start. */
    static final class BusyException extends RuntimeException {
        BusyException() {
            super("An execution is already running; wait for it to end or cancel it");
        }
    }

    /** The execution could not start: the request or the configuration of the workspace is invalid. */
    static final class StartException extends RuntimeException {
        StartException(String message) {
            super(message);
        }
    }

    /** One event of an execution, numbered from one in the order they happened. */
    record Event(int id, String name, Map<String, Object> data) {
    }

    /** Where an execution is, as a consistent view of it. */
    record Snapshot(Map<String, Object> run, List<Event> events, boolean finished) {
    }

    private final Path workspace;
    private final Map<String, String> environment;
    private Run current;
    private int ids;

    Executions(Path workspace, Map<String, String> environment) {
        this.workspace = workspace;
        this.environment = environment;
    }

    /**
     * Starts an execution and returns once it is under way.
     *
     * @param paths folders, files or scenarios ({@code orders/basket.feature:3}) relative to the
     *     features directory; empty for every feature
     * @throws BusyException if another execution is running
     * @throws StartException if the execution could not start
     */
    Map<String, Object> start(String profile, List<String> paths, String tags) {
        Run run;
        synchronized (this) {
            if (current != null && !current.finished) {
                throw new BusyException();
            }
            run = new Run(++ids, profile, paths, tags);
            current = run;
        }
        List<String> selected = paths.stream().map(path -> FEATURES + path.replaceFirst("^/+", "")).toList();
        ExecutionRequest request = new ExecutionRequest(
                workspace, profile, environment, new Selection(selected, tags));
        Thread thread = new Thread(() -> run.execute(request), "execution-" + run.id);
        run.thread = thread;
        thread.setDaemon(true);
        thread.start();
        try {
            run.started.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            run.cancellation.cancel();
            throw new StartException("Interrupted while the execution was starting");
        } catch (ExecutionException e) {
            throw new StartException(e.getCause().getMessage());
        }
        return run.summary();
    }

    /** Asks the running execution to stop; returns whether there was one. */
    boolean cancel() {
        Run run;
        synchronized (this) {
            run = current;
        }
        if (run == null || run.finished) {
            return false;
        }
        run.cancellation.cancel();
        return true;
    }

    /** The current execution, or the last one if none is running; {@code null} if none ever ran. */
    synchronized Run latest() {
        return current;
    }

    /** Stops the running execution, if any, and waits briefly for it to write its reports. */
    void shutdown() {
        Run run = latest();
        if (run != null && cancel() && run.thread != null) {
            try {
                run.thread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    final class Run {

        private final int id;
        private final String profile;
        private final List<String> paths;
        private final String tags;
        private final Instant startedAt = Instant.now();
        private final Cancellation cancellation = new Cancellation();
        private final CompletableFuture<Void> started = new CompletableFuture<>();
        private final List<Event> events = new ArrayList<>();
        private volatile boolean finished;
        private volatile Thread thread;
        private String status = "starting";
        private String error;
        private Instant finishedAt;
        /** The feature and scenario whose steps are being told. */
        private String feature;
        private int line;

        private Run(int id, String profile, List<String> paths, String tags) {
            this.id = id;
            this.profile = profile;
            this.paths = List.copyOf(paths);
            this.tags = tags;
        }

        private void execute(ExecutionRequest request) {
            try {
                ExecutionResult result = Engine.run(request, new Progress(), cancellation);
                finish(result);
            } catch (Throwable e) {
                String message = e.getMessage() != null ? e.getMessage() : e.toString();
                if (!started.completeExceptionally(new StartException(message))) {
                    // It was under way: the people watching learn it from the events.
                    synchronized (this) {
                        error = message;
                    }
                    publish("run-finished", terminal("error", null));
                } else {
                    synchronized (this) {
                        error = message;
                        status = "error";
                        finishedAt = Instant.now();
                        finished = true;
                        notifyAll();
                    }
                }
            }
        }

        private void finish(ExecutionResult result) {
            String outcome = cancellation.isCancelled() ? "cancelled" : result.successful() ? "passed" : "failed";
            Map<String, Object> data = terminal(outcome, result);
            publish("run-finished", data);
        }

        private Map<String, Object> terminal(String outcome, ExecutionResult result) {
            Map<String, Object> data = summary();
            data.put("status", outcome);
            data.put("finishedAt", Instant.now().toString());
            if (error != null) {
                data.put("error", error);
            }
            if (result != null) {
                data.put("scenarios", result.scenarios().size());
                data.put("failed", result.scenarios().stream()
                        .filter(scenario -> scenario.status() != Status.PASSED).count());
                data.put("noScenarioSelected", result.noScenarioSelected());
                if (result.reports() != null) {
                    data.put("reports", workspace.toAbsolutePath().normalize().relativize(
                            result.reports().toAbsolutePath().normalize()).toString().replace('\\', '/'));
                }
            }
            return data;
        }

        /** The identity and the state of the execution. */
        synchronized Map<String, Object> summary() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", id);
            data.put("status", status);
            data.put("profile", profile);
            data.put("paths", paths);
            data.put("tags", tags);
            data.put("startedAt", startedAt.toString());
            if (finishedAt != null) {
                data.put("finishedAt", finishedAt.toString());
            }
            if (error != null) {
                data.put("error", error);
            }
            return data;
        }

        synchronized Snapshot snapshot() {
            return new Snapshot(summary(), List.copyOf(events), finished);
        }

        /**
         * Waits until the execution has more events than {@code seen} or ends.
         *
         * @return whether there is something new to tell
         */
        synchronized boolean awaitEventsAfter(int seen, long timeoutMillis) throws InterruptedException {
            if (events.size() <= seen && !finished) {
                wait(timeoutMillis);
            }
            return events.size() > seen || finished;
        }

        private synchronized void publish(String name, Map<String, Object> data) {
            events.add(new Event(events.size() + 1, name, data));
            switch (name) {
                case "run-started" -> status = "running";
                case "run-finished" -> {
                    status = (String) data.get("status");
                    finishedAt = Instant.now();
                    finished = true;
                }
                default -> {
                }
            }
            notifyAll();
        }

        private Map<String, Object> data(Object... pairs) {
            Map<String, Object> data = new LinkedHashMap<>();
            for (int i = 0; i < pairs.length; i += 2) {
                data.put((String) pairs[i], pairs[i + 1]);
            }
            return data;
        }

        private static String relative(String feature) {
            return feature.startsWith(FEATURES) ? feature.substring(FEATURES.length()) : feature;
        }

        private static String status(Status status) {
            return status.name().toLowerCase();
        }

        /** Turns what the engine tells into events. */
        private final class Progress implements ExecutionListener {

            @Override
            public void started() {
                publish("run-started", summary());
                started.complete(null);
            }

            @Override
            public void featureStarted(String name) {
                feature = relative(name);
                publish("feature-started", data("feature", feature));
            }

            @Override
            public void scenarioStarted(String name, int scenarioLine, String scenario) {
                line = scenarioLine;
                publish("scenario-started", data("feature", relative(name), "line", scenarioLine, "name", scenario));
            }

            @Override
            public void stepStarted(String keyword, String text) {
                publish("step-started", data("feature", feature, "line", line, "keyword", keyword, "text", text));
            }

            @Override
            public void stepFinished(StepResult step) {
                Map<String, Object> data = data("feature", feature, "line", line,
                        "keyword", step.keyword(), "text", step.text(), "status", status(step.status()));
                if (step.error() != null) {
                    data.put("error", step.error());
                }
                publish("step-finished", data);
            }

            @Override
            public void scenarioFinished(ScenarioResult scenario) {
                publish("scenario-finished", data("feature", relative(scenario.feature()), "line", scenario.line(),
                        "name", scenario.name(), "status", status(scenario.status())));
            }

            @Override
            public void featureFinished(String name, Status status) {
                publish("feature-finished", data("feature", relative(name), "status", status(status)));
            }
        }
    }
}
