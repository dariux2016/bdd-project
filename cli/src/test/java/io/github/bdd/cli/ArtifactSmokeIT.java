package io.github.bdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Smoke test of the artifact: the executable jar, as packaged, runs the example PostgreSQL workspace
 * on a container it starts and exits successfully, leaving the reports. It is a failsafe test, so it
 * runs in {@code mvn verify} once the jar exists, and it needs Docker.
 */
class ArtifactSmokeIT {

    private static final Path JAR = Path.of("target", "bdd-project.jar");

    @TempDir
    Path directory;

    @Test
    void jarRunsTheExampleWorkspaceAndWritesTheReports() throws Exception {
        assumeTrue(Files.isRegularFile(JAR), "The jar has not been packaged");
        assumeTrue(dockerIsAvailable(), "Docker is not available");
        Path workspace = directory.resolve("workspace");
        copy(Path.of("..", "examples", "postgresql"), workspace);

        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", JAR.toAbsolutePath().toString(), "run", workspace.toString(), "--profile", "local")
                .redirectErrorStream(true)
                .redirectOutput(directory.resolve("output.txt").toFile())
                .start();
        boolean finished = process.waitFor(10, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
        }
        String output = Files.readString(directory.resolve("output.txt"));

        assertThat(finished).describedAs(output).isTrue();
        assertThat(process.exitValue()).describedAs(output).isZero();
        assertThat(output).contains("6 scenarios: 6 passed");
        try (Stream<Path> runs = Files.list(workspace.resolve("reports"))) {
            Path report = runs.findFirst().orElseThrow();
            assertThat(report.resolve("cucumber.json")).isNotEmptyFile();
            assertThat(report.resolve("junit.xml")).isNotEmptyFile();
            assertThat(report.resolve("report.html")).isNotEmptyFile();
            assertThat(Files.readString(report.resolve("summary.json"))).containsPattern("\"successful\"\\s*:\\s*true");
        }
    }

    private static boolean dockerIsAvailable() {
        try {
            Process docker = new ProcessBuilder("docker", "info").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return docker.waitFor(30, TimeUnit.SECONDS) && docker.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path path : paths.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
