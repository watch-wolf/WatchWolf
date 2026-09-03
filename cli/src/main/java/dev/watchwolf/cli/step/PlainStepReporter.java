package dev.watchwolf.cli.step;

import java.io.PrintStream;
import java.util.List;

/**
 * Prints per-step outcomes with the {@code [v]/[w]/[e]/[i]} prefixes the other WatchWolf scripts
 * use, and ends with a summary that repeats every failure and its remedy -- so the actionable part
 * is at the bottom of the terminal rather than scrolled away.
 */
public final class PlainStepReporter implements StepReporter {
    private final PrintStream out;
    private final PrintStream err;

    public PlainStepReporter(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static PlainStepReporter toStdout() {
        return new PlainStepReporter(System.out, System.err);
    }

    @Override
    public void runStarting(int totalSteps) {
        this.out.println("[i] " + totalSteps + " step(s) to run.");
    }

    @Override
    public void stepStarting(Step step, int index, int total) {
        this.out.printf("%n[v] (%d/%d) %s%n", index, total, step.title());
    }

    @Override
    public void stepFinished(StepResult result) {
        switch (result.outcome()) {
            case OK -> this.out.println("[v]   -> ok");
            case ALREADY_DONE -> this.out.println("[v]   -> already done (" + result.why() + ")");
            case SKIPPED -> this.out.println("[v]   -> skipped (" + result.why() + ")");
            case BLOCKED -> this.err.println("[w]   -> blocked: " + result.why());
            case FAILED, VERIFY_FAILED -> {
                this.err.println("[e]   -> " + result.outcome().label());
                this.err.println("[e]      " + result.what() + ": " + result.why());
                this.err.println("[e]      remedy: " + result.remedy());
            }
        }
    }

    @Override
    public void runFinished(List<StepResult> results) {
        List<StepResult> failures = results.stream().filter(r -> r.outcome().isFailure()).toList();
        List<StepResult> blocked = results.stream()
                .filter(r -> r.outcome() == StepOutcome.BLOCKED).toList();

        this.out.println();
        if (failures.isEmpty() && blocked.isEmpty()) {
            long done = results.stream().filter(r -> r.outcome().satisfied()).count();
            this.out.println("[i] All done. " + done + "/" + results.size()
                    + " step(s) satisfied, each verified.");
            return;
        }

        this.err.println("[e] " + failures.size() + " step(s) failed"
                + (blocked.isEmpty() ? "" : ", " + blocked.size() + " blocked") + ":");
        this.err.println();
        for (StepResult failure : failures) {
            this.err.println("      " + failure.id() + "  " + failure.outcome().label());
            this.err.println("        " + failure.what() + ": " + failure.why());
            this.err.println("        remedy: " + failure.remedy());
            this.err.println();
        }
        for (StepResult step : blocked) {
            this.err.println("      " + step.id() + "  blocked: " + step.why());
        }
    }
}
