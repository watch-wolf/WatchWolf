package dev.watchwolf.cli.step;

import java.util.Set;

/**
 * One unit of install work, paired with the check that proves it happened.
 *
 * <p>The pairing is the point. The Bash installer this replaces reports nothing about whether a
 * step worked -- a half-finished clone and a Spigot build whose container died both look like
 * success -- so every step here declares a {@link Verification} and the runner always runs it.
 */
public interface Step {

    StepId id();

    /** Shown in the menu, the progress log and the summary. */
    String title();

    /** Ids that must have succeeded before this can run. */
    default Set<StepId> requires() {
        return Set.of();
    }

    /** False when the plan deselected this, or it makes no sense here. */
    default boolean isApplicable(StepContext context) {
        return true;
    }

    /** Why it was skipped, shown to the user. Only consulted when {@link #isApplicable} is false. */
    default String skipReason(StepContext context) {
        return "not selected";
    }

    /** @throws StepFailedException with a what/why/remedy the user can act on */
    void perform(StepContext context) throws StepFailedException;

    /** Never null. Choose {@link Verification#nothingToVerify} explicitly if there is nothing. */
    Verification verification();
}
