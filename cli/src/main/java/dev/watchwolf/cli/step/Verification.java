package dev.watchwolf.cli.step;

/**
 * A step's postcondition. Answers "I just cloned the repository -- is it really cloned?".
 *
 * <p>Run <b>twice</b>: once before {@link Step#perform} (already satisfied -> the step is skipped,
 * which is what makes {@code build} idempotent and lets it stop deleting the install directory)
 * and once after (does the work actually hold?).
 */
public interface Verification {

    /** What this asserts, in words -- shown by {@code --dry-run} and in the failure message. */
    String describe();

    /** @throws VerificationFailedException when the postcondition does not hold */
    void check(StepContext context) throws VerificationFailedException;

    /**
     * For the rare step with nothing meaningful to assert.
     *
     * <p>A named class rather than {@code null}, so choosing "nothing to verify" is a visible
     * decision in the source and in review -- not an omission.
     */
    static Verification nothingToVerify(String why) {
        return new Verification() {
            @Override public String describe()                  { return "nothing to verify (" + why + ")"; }
            @Override public void check(StepContext context)     { }
        };
    }
}
