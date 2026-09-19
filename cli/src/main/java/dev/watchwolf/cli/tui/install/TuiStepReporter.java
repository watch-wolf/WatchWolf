package dev.watchwolf.cli.tui.install;

import dev.watchwolf.cli.step.Step;
import dev.watchwolf.cli.step.StepReporter;
import dev.watchwolf.cli.step.StepResult;

import java.util.List;

/**
 * Routes per-step outcomes into {@link InstallProgressModel} instead of stdout.
 *
 * <p>{@code runFinished} deliberately does NOT mark the model finished: only the caller knows
 * whether the run ended on its own, was aborted, or was sent to the background, and the screen
 * shows a different thing for each. See {@link InstallProgressModel.Ending}.
 */
public final class TuiStepReporter implements StepReporter {
    private final InstallProgressModel model;

    public TuiStepReporter(InstallProgressModel model) {
        this.model = model;
    }

    @Override
    public void runStarting(int totalSteps) {
        this.model.runStarting(totalSteps);
    }

    @Override
    public void stepStarting(Step step, int index, int total) {
        this.model.stepStarting(step.id().value(), step.title());
    }

    @Override
    public void stepFinished(StepResult result) {
        this.model.stepFinished(result.id().value(), result.title(), result.outcome());
    }

    @Override
    public void runFinished(List<StepResult> results) {
        // see the class Javadoc: the ending is the caller's to decide
    }
}
