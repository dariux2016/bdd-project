package io.github.bdd.cli;

import io.github.bdd.engine.Engine;
import io.github.bdd.engine.ExecutionRequest;
import io.github.bdd.engine.ExecutionResult;
import io.github.bdd.engine.ScenarioResult;
import io.github.bdd.engine.Selection;
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
    private static final int EXIT_NO_SCENARIO_SELECTED = 3;

    private static final String USAGE = "Usage: run [workspace] [--profile <name>] [--path <path[:line]>]... "
            + "[--tags <expression>] [--fail-on-pending]";

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
        String tags = null;
        List<String> paths = new ArrayList<>();
        boolean failOnPending = false;
        for (int i = 1; i < args.length; i++) {
            boolean hasValue = i + 1 < args.length && !args[i + 1].startsWith("--");
            if (args[i].equals("--fail-on-pending") && !failOnPending) {
                failOnPending = true;
            } else if (args[i].equals("--profile") && profile == null && hasValue) {
                profile = args[++i];
            } else if (args[i].equals("--tags") && tags == null && hasValue) {
                tags = args[++i];
            } else if (args[i].equals("--path") && hasValue) {
                paths.add(args[++i]);
            } else if (!args[i].startsWith("--") && workspace == null) {
                workspace = args[i];
            } else {
                err.println(USAGE);
                return EXIT_ERROR;
            }
        }

        ExecutionResult result;
        try {
            result = Engine.run(new ExecutionRequest(Path.of(workspace == null ? "" : workspace), profile,
                    System.getenv(), new Selection(paths, tags), failOnPending));
        } catch (RuntimeException e) {
            err.println(e.getMessage());
            return EXIT_ERROR;
        }

        print(result, out);
        if (result.noScenarioSelected()) {
            err.println("No scenario matches the selection: nothing was executed");
            return EXIT_NO_SCENARIO_SELECTED;
        }
        return result.successful(failOnPending) ? EXIT_SUCCESS : EXIT_SCENARIOS_NOT_PASSED;
    }

    private static void print(ExecutionResult result, PrintStream out) {
        for (ScenarioResult scenario : result.scenarios()) {
            out.printf("%-10s %s:%d %s%n", scenario.status(), scenario.feature(), scenario.line(), scenario.name());
            for (StepResult step : scenario.steps()) {
                out.printf("  %-10s %s %s%n", step.status(), step.keyword(), step.text());
                printInner(step.steps(), "    ", out);
                if (step.error() != null) {
                    step.error().lines().forEach(line -> out.printf("  %-10s %s%n", "", line));
                }
            }
        }
        out.println();
        out.println(summary(result.scenarios()));
        if (result.reports() != null) {
            out.println("Reports: " + result.reports());
        }
    }

    /** The steps of a composite phrase, below it; why one failed is in the error of the composite step. */
    private static void printInner(List<StepResult> steps, String indent, PrintStream out) {
        for (StepResult step : steps) {
            out.printf("  %-10s %s%s %s%n", "", indent, step.status().name().toLowerCase(), step.text());
            printInner(step.steps(), indent + "  ", out);
        }
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
