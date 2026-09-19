package dev.watchwolf.cli.validation;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

/**
 * Asserts that the parsers, models and the two TUI models stay free of I/O, so the unit suite can
 * keep exercising them with no Docker daemon, no network and no {@code $HOME/WatchWolf}.
 *
 * <p>This is the check that keeps §8b's seam design honest as the module grows: it is easy for one
 * convenience import to quietly turn a "pure" class into one that only works with a live daemon.
 * One dynamic test per file, so a violation names the offending file and the forbidden import.
 */
public class KeepPureLogicPureShould {

    /** Directories whose contents must be entirely free of the imports below. */
    private static final List<Path> PURE_DIRECTORIES = List.of(
            Paths.get("src/main/java/dev/watchwolf/cli/model"),
            Paths.get("src/main/java/dev/watchwolf/cli/parse"));

    /**
     * Individual files outside those directories that are still meant to be pure: the two TUI
     * models and their supporting value types, which is what lets "pressing space toggles a
     * checkbox" or "Enter opens the log for one entity" be tested without a terminal.
     */
    private static final List<Path> PURE_FILES = List.of(
            Paths.get("src/main/java/dev/watchwolf/cli/layout/InstallLayout.java"),
            Paths.get("src/main/java/dev/watchwolf/cli/layout/RuntimeFlavor.java"),
            Paths.get("src/main/java/dev/watchwolf/cli/tui/Async.java"),
            Paths.get("src/main/java/dev/watchwolf/cli/tui/menu/MenuNode.java"),
            Paths.get("src/main/java/dev/watchwolf/cli/tui/menu/MenuModel.java"),
            Paths.get("src/main/java/dev/watchwolf/cli/tui/monitor/MonitorRow.java"),
            Paths.get("src/main/java/dev/watchwolf/cli/tui/monitor/EntityView.java"),
            Paths.get("src/main/java/dev/watchwolf/cli/tui/monitor/MonitorModel.java"),
            Paths.get("src/main/java/dev/watchwolf/cli/tui/install/InstallProgressModel.java"),
            Paths.get("src/main/java/dev/watchwolf/cli/model/BuildPlanFile.java"),
            Paths.get("src/main/java/dev/watchwolf/cli/model/InstallRunRecord.java"));

    /**
     * Substring, not a parsed import list -- simpler, and "this text must never appear" is exactly
     * the property being asserted (it also catches a fully-qualified inline reference, which a
     * pure-import check would miss).
     */
    private static final List<String> FORBIDDEN = List.of(
            "com.github.dockerjava",
            "java.lang.ProcessBuilder",
            "new ProcessBuilder(",
            "java.net.Socket",
            "new Socket(",
            "java.nio.file.Files",
            "java.net.http.HttpClient");

    @TestFactory
    Stream<DynamicTest> keepPureLogicFreeOfDockerProcessSocketAndFileIO() {
        return this.pureFiles().map(file -> DynamicTest.dynamicTest(
                file.toString(), () -> this.assertNoForbiddenImport(file)));
    }

    private Stream<Path> pureFiles() {
        return Stream.concat(
                PURE_DIRECTORIES.stream().filter(Files::isDirectory).flatMap(this::javaFilesUnder),
                PURE_FILES.stream().filter(Files::exists));
    }

    private Stream<Path> javaFilesUnder(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList().stream();
        } catch (IOException ex) {
            throw new AssertionError("Could not walk " + directory, ex);
        }
    }

    private void assertNoForbiddenImport(Path file) throws IOException {
        String contents = Files.readString(file);
        for (String forbidden : FORBIDDEN) {
            if (contents.contains(forbidden)) {
                throw new AssertionError(file + " references '" + forbidden + "', which belongs "
                        + "behind a seam (DockerFacade / CommandRunner / FileGateway). This class "
                        + "is meant to be exercisable with no Docker daemon and no filesystem.");
            }
        }
    }
}
