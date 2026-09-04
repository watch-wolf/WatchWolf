package dev.watchwolf.cli.step;

import java.time.Duration;
import java.util.Optional;

/** What one step did, and why. */
public record StepResult(StepId id, String title, StepOutcome outcome, Duration elapsed,
                         String what, String why, String remedy) {

    public static StepResult ok(StepId id, String title, Duration elapsed) {
        return new StepResult(id, title, StepOutcome.OK, elapsed, null, null, null);
    }

    public static StepResult alreadyDone(StepId id, String title, String why) {
        return new StepResult(id, title, StepOutcome.ALREADY_DONE, Duration.ZERO, null, why, null);
    }

    public static StepResult skipped(StepId id, String title, String why) {
        return new StepResult(id, title, StepOutcome.SKIPPED, Duration.ZERO, null, why, null);
    }

    public static StepResult blocked(StepId id, String title, String why) {
        return new StepResult(id, title, StepOutcome.BLOCKED, Duration.ZERO, null, why,
                "Fix the step it depends on and run the command again.");
    }

    public static StepResult failed(StepId id, String title, Duration elapsed,
                                    StepFailedException failure) {
        StepOutcome outcome = (failure instanceof VerificationFailedException)
                ? StepOutcome.VERIFY_FAILED : StepOutcome.FAILED;
        return new StepResult(id, title, outcome, elapsed,
                failure.what(), failure.why(), failure.remedy());
    }

    public Optional<String> remedyText() {
        return Optional.ofNullable(this.remedy);
    }
}
