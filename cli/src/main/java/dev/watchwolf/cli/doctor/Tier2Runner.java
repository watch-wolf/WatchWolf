package dev.watchwolf.cli.doctor;

import dev.watchwolf.cli.model.TesterSuiteCatalog;
import dev.watchwolf.cli.proc.CommandResult;
import dev.watchwolf.cli.step.StepContext;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The end-to-end self-diagnosis: runs WatchWolf-Tester's own integration suites.
 *
 * <p>It shells out to {@code <base>/WatchWolf-Tester/ci/tests.sh --integration --tests <pattern>}
 * rather than reimplementing anything. That script is already non-interactive-safe (it only adds
 * {@code -it} when stdout is a TTY) and already preflights TCP 8000/7000 with an actionable
 * message, so the useful thing to add here is the surrounding context: which suites, why they were
 * chosen, and a log bundle when they fail.
 *
 * <p>It works from inside the CLI's container because of the launcher's identity mounts: the script
 * runs {@code docker run -v <testerRepo>:/compile -v $HOME/.m2:/root/.m2}, and both of those paths
 * mean the same thing inside and outside.
 */
public final class Tier2Runner {
    private static final Duration TIMEOUT = Duration.ofHours(2);

    public record Outcome(boolean passed, String detail, String remedy, Path reportsDirectory) { }

    public Outcome run(StepContext context, Set<String> suites) {
        Path script = context.layout().testerTestsScript();

        if (!context.files().exists(script)) {
            return new Outcome(false,
                    "WatchWolf-Tester is not installed at " + context.layout().testerRepo(),
                    "Run 'watchwolf build' with 'WatchWolf-Tester' selected to enable the "
                            + "end-to-end self-test.",
                    null);
        }

        if (suites.isEmpty()) {
            return new Outcome(false, "no suites were selected",
                    "Select at least one suite in 'watchwolf build', or pass "
                            + "--self-test-suites <names>.", null);
        }

        String pattern = TesterSuiteCatalog.testPatternFor(suites);
        context.progress().begin("Running the self-diagnosis: " + suites.size()
                + " Tester suite(s) [" + pattern + "]. Real servers start, so this takes minutes");

        List<String> argv = new ArrayList<>(List.of(
                "bash", script.toString(), "--integration", "--tests", pattern));

        CommandResult result = context.commands().run(context.layout().testerRepo(),
                Map.of("HOME", System.getenv().getOrDefault("HOME", "/root")),
                TIMEOUT, context.progress(), argv);

        Path reports = context.layout().testerRepo()
                .resolve("target").resolve("failsafe-reports");

        if (result.succeeded()) {
            context.progress().end("passed");
            return new Outcome(true, suites.size() + " suite(s) passed", null, reports);
        }

        context.progress().end("failed");
        return new Outcome(false,
                "ci/tests.sh exited " + result.exitCode() + ": " + firstUsefulLine(result),
                "The full report is in " + reports + ". A diagnostics bundle was written too -- "
                        + "attach it to a bug report rather than pasting stack traces.",
                reports);
    }

    /** The Tester's preflight already prints something actionable; surface that, not the tail. */
    private static String firstUsefulLine(CommandResult result) {
        for (String line : result.stderr()) {
            if (line.startsWith("[e]")) return line.substring(3).strip();
        }
        for (String line : result.stdout()) {
            if (line.contains("Tests run:") && line.contains("Failures:")) return line.strip();
        }
        String failure = result.failureText();
        if (failure.isBlank()) return "no output";
        return failure.length() > 300 ? failure.substring(0, 300) + "..." : failure;
    }
}
