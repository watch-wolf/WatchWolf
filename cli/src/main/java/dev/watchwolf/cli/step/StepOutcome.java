package dev.watchwolf.cli.step;

/** How a step ended. */
public enum StepOutcome {
    /** Performed, and its verification passed. */
    OK,
    /** Its verification already held, so no work was needed. This is what makes build idempotent. */
    ALREADY_DONE,
    /** Deselected, or not applicable to this plan. */
    SKIPPED,
    /** {@link Step#perform} raised. */
    FAILED,
    /**
     * Performed without error, but the postcondition does not hold.
     *
     * <p>Kept distinct from {@link #FAILED} because it points somewhere else entirely: the
     * command lied, or something removed its work afterwards.
     */
    VERIFY_FAILED,
    /** A step it depends on did not succeed, so this never ran. */
    BLOCKED;

    public boolean isFailure() {
        return this == FAILED || this == VERIFY_FAILED;
    }

    public boolean satisfied() {
        return this == OK || this == ALREADY_DONE;
    }

    public String label() {
        return switch (this) {
            case OK -> "ok";
            case ALREADY_DONE -> "already done";
            case SKIPPED -> "skipped";
            case FAILED -> "FAILED";
            case VERIFY_FAILED -> "PERFORMED BUT UNVERIFIED";
            case BLOCKED -> "blocked";
        };
    }
}
