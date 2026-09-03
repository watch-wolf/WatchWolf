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
 * A file that breaks these conventions is <b>silently never executed</b> by Maven -- Surefire only
 * picks up {@code *Should.java} in the default suite, and Failsafe only picks up {@code IT*.java}.
 * That is how WatchWolf-Tester ended up with a suite nobody had run in years, and the reason these
 * are ordinary JUnit tests rather than a comment: one dynamic test per file, so a violation reports
 * individually and names the offending file, the same shape as
 * WatchWolf-Tester's own {@code src/validation-test/java}.
 *
 * <p>Only files that actually declare a test are checked -- a fixture like
 * {@code fake/FakeDockerFacade.java} legitimately lives under {@code src/test/java} without ending
 * in "Should", and flagging it would just be noise.
 */
public class NamingConventionsShould {
    private static final Path UNIT_TESTS_ROOT = Paths.get("src/test/java");
    private static final Path INTEGRATION_TESTS_ROOT = Paths.get("src/integration-test/java");

    @TestFactory
    Stream<DynamicTest> nameEveryUnitTestWithTheShouldSuffix() {
        return javaFilesDeclaringATest(UNIT_TESTS_ROOT).map(file -> DynamicTest.dynamicTest(
                file.toString(),
                () -> {
                    String name = file.getFileName().toString();
                    if (!name.endsWith("Should.java")) {
                        throw new AssertionError(name + " declares a @Test/@TestFactory under "
                                + UNIT_TESTS_ROOT + " but does not end with 'Should' -- Surefire's "
                                + "default-test execution only includes **/*Should.java, so this "
                                + "class silently never runs.");
                    }
                }));
    }

    @TestFactory
    Stream<DynamicTest> nameEverySystemTestWithTheItPrefix() {
        return javaFilesDeclaringATest(INTEGRATION_TESTS_ROOT).map(file -> DynamicTest.dynamicTest(
                file.toString(),
                () -> {
                    String name = file.getFileName().toString();
                    if (!name.startsWith("IT")) {
                        throw new AssertionError(name + " declares a @Test/@TestFactory under "
                                + INTEGRATION_TESTS_ROOT + " but does not start with 'IT' -- "
                                + "Failsafe will never run it.");
                    }
                }));
    }

    /** Files containing an actual test annotation -- fixtures and helpers are not checked. */
    private static Stream<Path> javaFilesDeclaringATest(Path root) {
        if (!Files.isDirectory(root)) return Stream.empty();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(NamingConventionsShould::declaresATest)
                    .toList();
            return files.stream();
        } catch (IOException ex) {
            throw new UncheckedIOFailure(ex);
        }
    }

    private static boolean declaresATest(Path file) {
        try {
            String contents = Files.readString(file);
            return contents.contains("@Test") || contents.contains("@TestFactory");
        } catch (IOException ex) {
            return false;
        }
    }

    private static final class UncheckedIOFailure extends RuntimeException {
        UncheckedIOFailure(IOException cause) {
            super(cause);
        }
    }
}
