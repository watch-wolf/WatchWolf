package dev.watchwolf.cli.log;

import dev.watchwolf.cli.step.Step;
import dev.watchwolf.cli.step.StepReporter;
import dev.watchwolf.cli.step.StepResult;

import java.util.List;

/**
 * Tees a {@link StepReporter} into the {@link RunLog}, so the file carries the same account of the
 * run the terminal got -- including, for a drawn install, the account the terminal never got.
 *
 * <p>Failures are written with their {@code what}/{@code why}/{@code remedy} in full: a log that
 * says "step 7 failed" and nothing else would send the reader back to the machine they no longer
 * have.
 */
public final class RunLogStepReporter implements StepReporter {
    private final StepReporter delegate;
    private final RunLog log;

    public RunLogStepReporter(StepReporter delegate, RunLog log) {
        this.delegate = delegate;
        this.log = log;
    }

    @Override
    public void runStarting(int totalSteps) {
        this.log.line("[i] " + totalSteps + " step(s) to run.");
        this.delegate.runStarting(totalSteps);
    }

    @Override
    public void stepStarting(Step step, int index, int total) {
        this.log.line("[v] (" + index + "/" + total + ") " + step.title());
        this.delegate.stepStarting(step, index, total);
    }

    @Override
    public void stepFinished(StepResult result) {
        switch (result.outcome()) {
            case OK -> this.log.line("[v]   -> ok");
            case ALREADY_DONE -> this.log.line("[v]   -> already done (" + result.why() + ")");
            case SKIPPED -> this.log.line("[v]   -> skipped (" + result.why() + ")");
            // blocked steps never get a stepStarting line, so this one has to name itself
            case BLOCKED -> this.log.line("[w] " + result.title() + " -> blocked: " + result.why());
            case FAILED, VERIFY_FAILED -> {
                this.log.line("[e]   -> " + result.outcome().label());
                this.log.line("[e]      " + result.what() + ": " + result.why());
                this.log.line("[e]      remedy: " + result.remedy());
            }
        }
        this.delegate.stepFinished(result);
    }

    @Override
    public void runFinished(List<StepResult> results) {
        long failed = results.stream().filter(result -> result.outcome().isFailure()).count();
        long satisfied = results.stream().filter(result -> result.outcome().satisfied()).count();
        this.log.line(failed == 0
                ? "[i] All done. " + satisfied + "/" + results.size() + " step(s) satisfied."
                : "[e] " + failed + " step(s) failed of " + results.size() + ".");
        this.delegate.runFinished(results);
    }
}
