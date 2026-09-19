package dev.watchwolf.cli.step;

/**
 * A step ran without error but its postcondition does not hold.
 *
 * <p>This is the case the old installer could not express, and the reason the whole framework
 * exists: {@code git clone} exiting 0 having written half a repository, a Spigot build whose
 * container died leaving a truncated jar. "Performed but unverified" is reported distinctly from
 * "failed" because it means something quite different to whoever has to fix it.
 */
public class VerificationFailedException extends StepFailedException {
    public VerificationFailedException(String what, String why, String remedy) {
        super(what, why, remedy);
    }

    public VerificationFailedException(String what, String why, String remedy, Throwable cause) {
        super(what, why, remedy, cause);
    }
}
