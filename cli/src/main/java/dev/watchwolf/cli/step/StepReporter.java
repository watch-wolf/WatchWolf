package dev.watchwolf.cli.step;

import java.util.List;

/** Where a run's per-step outcomes go. */
public interface StepReporter {

    void runStarting(int totalSteps);

    void stepStarting(Step step, int index, int total);

    void stepFinished(StepResult result);

    void runFinished(List<StepResult> results);

    static StepReporter discarding() {
        return new StepReporter() {
            @Override public void runStarting(int totalSteps) { }
            @Override public void stepStarting(Step step, int index, int total) { }
            @Override public void stepFinished(StepResult result) { }
            @Override public void runFinished(List<StepResult> results) { }
        };
    }
}
