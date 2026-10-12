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

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(execute(args, System.out, System.err));
    }

    static int execute(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 1 || args.length > 2 || !args[0].equals("run")) {
            err.println("Usage: run [workspace]");
            return EXIT_ERROR;
        }
        Path workspace = Path.of(args.length == 2 ? args[1] : "");

        ExecutionResult result;
        try {
            result = Engine.run(new ExecutionRequest(workspace));
        } catch (IllegalArgumentException | IllegalStateException e) {
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
