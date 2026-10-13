package io.github.bdd.cli;

import io.github.bdd.engine.Engine;
import io.github.bdd.engine.ExecutionRequest;
import io.github.bdd.engine.ExecutionResult;
import io.github.bdd.engine.ScenarioResult;
import io.github.bdd.engine.Status;
import io.github.bdd.engine.StepResult;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Main {

    private static final int EXIT_SUCCESS = 0;
    private static final int EXIT_SCENARIOS_NOT_PASSED = 1;
    private static final int EXIT_ERROR = 2;

    private static final String USAGE = "Usage: run [workspace] [--profile <name>]";

    private Main() {
    }

    public static void main(String[] args) {
        int code = args.length > 0 && args[0].equals("serve")
                ? Serve.execute(args, System.out, System.err)
                : execute(args, System.out, System.err);
        if (code >= 0) {
            System.exit(code);
        }
    }

    static int execute(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 1 || !args[0].equals("run")) {
            err.println(USAGE);
            return EXIT_ERROR;
        }
        String workspace = null;
        String profile = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--profile") && profile == null && i + 1 < args.length
                    && !args[i + 1].startsWith("--")) {
                profile = args[++i];
            } else if (!args[i].startsWith("--") && workspace == null) {
                workspace = args[i];
            } else {
                err.println(USAGE);
                return EXIT_ERROR;
            }
        }

        ExecutionResult result;
        try {
            result = Engine.run(new ExecutionRequest(Path.of(workspace == null ? "" : workspace), profile));
        } catch (RuntimeException e) {
            err.println(e.getMessage());
            return EXIT_ERROR;
        }

        print(result, out);
        return result.successful() ? EXIT_SUCCESS : EXIT_SCENARIOS_NOT_PASSED;
    }

    private static void print(ExecutionResult result, PrintStream out) {
        for (ScenarioResult scenario : result.scenarios()) {
            out.printf("%-10s %s:%d %s%n", scenario.status(), scenario.feature(), scenario.line(), scenario.name());
            for (StepResult step : scenario.steps()) {
                out.printf("  %-10s %s %s%n", step.status(), step.keyword(), step.text());
                if (step.error() != null) {
                    step.error().lines().forEach(line -> out.printf("  %-10s %s%n", "", line));
                }
            }
        }
        out.println();
        out.println(summary(result.scenarios()));
    }

    private static String summary(List<ScenarioResult> scenarios) {
        List<String> counts = new ArrayList<>();
        for (Status status : Status.values()) {
            long count = scenarios.stream().filter(scenario -> scenario.status() == status).count();
            if (count > 0) {
                counts.add(count + " " + status.name().toLowerCase());
            }
        }
        String total = scenarios.size() + (scenarios.size() == 1 ? " scenario" : " scenarios");
        return counts.isEmpty() ? total : total + ": " + String.join(", ", counts);
    }
}
