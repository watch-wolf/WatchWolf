package dev.watchwolf.cli.step;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Performs a {@link StepGraph}, verifying every step.
 *
 * <p>Three behaviours that are the point of the whole design:
 *
 * <ul>
 *   <li><b>The verification runs before the work too.</b> Already satisfied means
 *       {@link StepOutcome#ALREADY_DONE} and nothing is done, so {@code build} is idempotent --
 *       which is what lets it stop deleting the install directory the way the Bash script did.</li>
 *   <li><b>A deselected step is still verified when something depends on it.</b> So
 *       {@code --skip-spigot-build} against an empty {@code server-types/} fails immediately with
 *       a named cause, rather than three minutes into the first test run.</li>
 *   <li><b>A failure blocks only its dependents.</b> The run continues, so one pass surfaces every
 *       independent problem instead of one per attempt. {@code --fail-fast} reverses that.</li>
 * </ul>
 */
public final class StepRunner {
    private final StepReporter reporter;
    private final boolean failFast;
    private final boolean verifyOnly;

    public StepRunner(StepReporter reporter, boolean failFast, boolean verifyOnly) {
        this.reporter = reporter;
        this.failFast = failFast;
        this.verifyOnly = verifyOnly;
    }

    public static StepRunner reporting(StepReporter reporter) {
        return new StepRunner(reporter, false, false);
    }

    public StepRunner failingFast() {
        return new StepRunner(this.reporter, true, this.verifyOnly);
    }

    /** Runs every verification and no work -- a free second doctor. */
    public StepRunner verifyingOnly() {
        return new StepRunner(this.reporter, this.failFast, true);
    }

    public List<StepResult> run(StepGraph graph, StepContext context) {
        List<Step> steps = graph.ordered();
        List<StepResult> results = new ArrayList<>(steps.size());
        Map<StepId, StepOutcome> outcomes = new LinkedHashMap<>();

        this.reporter.runStarting(steps.size());

        int index = 0;
        for (Step step : steps) {
            index++;

            Set<StepId> unsatisfied = unsatisfiedDependencies(step, outcomes);
            if (!unsatisfied.isEmpty()) {
                StepResult blocked = StepResult.blocked(step.id(), step.title(),
                        "depends on " + String.join(", ", unsatisfied.stream()
                                .map(StepId::value).toList()));
                outcomes.put(step.id(), blocked.outcome());
                results.add(blocked);
                this.reporter.stepFinished(blocked);
                continue;
            }

            this.reporter.stepStarting(step, index, steps.size());
            StepResult result = this.runOne(step, context);
            outcomes.put(step.id(), result.outcome());
            results.add(result);
            this.reporter.stepFinished(result);

            if (this.failFast && result.outcome().isFailure()) break;
        }

        this.reporter.runFinished(results);
        return results;
    }

    private StepResult runOne(Step step, StepContext context) {
        boolean applicable = step.isApplicable(context);
        Verification verification = step.verification();
        long startedAt = System.nanoTime();

        // 1. Is it already satisfied? A deselected step is still checked here, so anything
        //    depending on it either finds it in place or fails with a named cause.
        boolean alreadySatisfied = false;
        try {
            verification.check(context);
            alreadySatisfied = true;
        } catch (VerificationFailedException expected) {
            // not done yet -- the normal case on a fresh install
        } catch (RuntimeException ex) {
            // a verification that itself blows up is a bug in the step, not a user error
            return StepResult.failed(step.id(), step.title(), elapsed(startedAt),
                    new StepFailedException("checking whether '" + step.title() + "' was already done",
                            describe(ex), "This is a bug in the CLI; please report it.", ex));
        }

        if (alreadySatisfied) {
            return StepResult.alreadyDone(step.id(), step.title(), verification.describe());
        }

        if (!applicable) {
            return StepResult.skipped(step.id(), step.title(), step.skipReason(context));
        }

        if (this.verifyOnly) {
            return StepResult.failed(step.id(), step.title(), elapsed(startedAt),
                    new VerificationFailedException(step.title(),
                            "not satisfied: " + verification.describe(),
                            "Run 'watchwolf build' to perform this step."));
        }

        // 2. Do the work.
        try {
            step.perform(context);
        } catch (StepFailedException failure) {
            return StepResult.failed(step.id(), step.title(), elapsed(startedAt), failure);
        } catch (RuntimeException ex) {
            return StepResult.failed(step.id(), step.title(), elapsed(startedAt),
                    new StepFailedException(step.title(), describe(ex),
                            "This was not an expected failure; re-run with --verbose for detail.", ex));
        }

        // 3. Prove it. Succeeding at the work and failing here means the command lied, or
        //    something removed the result -- a different problem, reported differently.
        try {
            verification.check(context);
        } catch (VerificationFailedException failure) {
            return StepResult.failed(step.id(), step.title(), elapsed(startedAt), failure);
        } catch (RuntimeException ex) {
            return StepResult.failed(step.id(), step.title(), elapsed(startedAt),
                    new VerificationFailedException(step.title(), describe(ex),
                            "This is a bug in the CLI; please report it."));
        }

        return StepResult.ok(step.id(), step.title(), elapsed(startedAt));
    }

    private static Set<StepId> unsatisfiedDependencies(Step step, Map<StepId, StepOutcome> outcomes) {
        Set<StepId> unsatisfied = new LinkedHashSet<>();
        for (StepId required : step.requires()) {
            StepOutcome outcome = outcomes.get(required);
            if (outcome == null || !outcome.satisfied()) unsatisfied.add(required);
        }
        return unsatisfied;
    }

    private static Duration elapsed(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos);
    }

    private static String describe(Throwable ex) {
        String message = ex.getMessage();
        return (message == null || message.isBlank()) ? ex.getClass().getSimpleName() : message;
    }
}
