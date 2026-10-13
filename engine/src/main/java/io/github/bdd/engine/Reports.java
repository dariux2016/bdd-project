package io.github.bdd.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The folder of the reports of one execution, under {@code <workspace>/reports/}, named by the
 * instant the execution started, and the retention of the folders of the older ones.
 */
final class Reports {

    static final String DIRECTORY = "reports";
    static final int DEFAULT_KEEP = 20;

    private static final YamlFile YAML = Settings.YAML;
    private static final DateTimeFormatter NAME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    /** The folders this class creates; anything else under {@code reports/} is not ours to delete. */
    private static final Pattern OWN_FOLDER = Pattern.compile("(\\d{8}-\\d{6}-\\d{3})(?:-(\\d+))?");

    private final Path directory;
    private final int keep;

    private Reports(Path directory, int keep) {
        this.directory = directory;
        this.keep = keep;
    }

    /**
     * Reads the retention from the {@code reports} section of the workspace's {@code settings.yaml};
     * what the file leaves out has the default.
     *
     * @throws ConfigurationException if the settings are invalid
     */
    static int loadKeep(Path workspace) {
        Map<String, Object> reports = Settings.section(workspace, "reports");
        YAML.allowOnly(reports, "reports", "keep");
        Object value = reports.get("keep");
        if (value == null) {
            return DEFAULT_KEEP;
        }
        if (!(value instanceof Integer keep) || keep < 1) {
            throw YAML.invalid("reports.keep", "expected a number of at least one but was " + YamlFile.show(value));
        }
        return keep;
    }

    /**
     * Creates the folder of an execution started at {@code start}, named with the instant in UTC
     * ({@code yyyyMMdd-HHmmss-SSS}, safe on every filesystem), made unique with a counter if an
     * execution started within the same millisecond.
     */
    static Reports create(Path workspace, Instant start) {
        int keep = loadKeep(workspace);
        Path root = workspace.resolve(DIRECTORY);
        String name = NAME.format(start);
        try {
            Files.createDirectories(root);
            Path directory = root.resolve(name);
            for (int attempt = 2; ; attempt++) {
                try {
                    Files.createDirectory(directory);
                    return new Reports(directory, keep);
                } catch (FileAlreadyExistsException e) {
                    directory = root.resolve(name + "-" + attempt);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the reports folder in " + root, e);
        }
    }

    Path directory() {
        return directory;
    }

    /** The Cucumber plugin options that write the JSON, JUnit XML and HTML reports. */
    List<String> pluginNames() {
        return List.of(
                "json:" + file("cucumber.json"),
                "junit:" + file("junit.xml"),
                "html:" + file("report.html"));
    }

    private String file(String name) {
        return directory.resolve(name).toString().replace('\\', '/');
    }

    /** Writes {@code summary.json}, the machine-readable outcome of the execution. */
    void writeSummary(String profile, Selection selection, Instant start, Duration duration,
            List<ScenarioResult> scenarios) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("total", scenarios.size());
        for (Status status : Status.values()) {
            counts.put(status.name().toLowerCase(),
                    scenarios.stream().filter(scenario -> scenario.status() == status).count());
        }
        Map<String, Object> selected = new LinkedHashMap<>();
        selected.put("paths", selection.paths());
        selected.put("tags", selection.tags());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("profile", profile);
        summary.put("selection", selected);
        summary.put("startedAt", start.toString());
        summary.put("durationMillis", duration.toMillis());
        summary.put("counts", counts);
        summary.put("noScenarioSelected", scenarios.isEmpty());
        summary.put("successful", !scenarios.isEmpty()
                && scenarios.stream().allMatch(scenario -> scenario.status() == Status.PASSED));
        try {
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValue(directory.resolve("summary.json").toFile(), summary);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the summary in " + directory, e);
        }
    }

    /** Deletes the folders of the oldest executions beyond the number to keep. */
    void prune() {
        List<Path> own = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory.getParent())) {
            for (Path child : children) {
                if (Files.isDirectory(child) && OWN_FOLDER.matcher(child.getFileName().toString()).matches()) {
                    own.add(child);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + directory.getParent(), e);
        }
        own.sort(Comparator.comparing((Path path) -> group(path, 1))
                .thenComparingInt(path -> group(path, 2) == null ? 1 : Integer.parseInt(group(path, 2))));
        for (Path old : own.subList(0, Math.max(0, own.size() - keep))) {
            delete(old);
        }
    }

    private static String group(Path path, int index) {
        var matcher = OWN_FOLDER.matcher(path.getFileName().toString());
        return matcher.matches() ? matcher.group(index) : null;
    }

    private static void delete(Path folder) {
        try (var paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete the old reports " + folder, e);
        }
    }
}
