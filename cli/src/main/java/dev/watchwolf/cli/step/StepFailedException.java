package dev.watchwolf.cli.step;

/**
 * A step could not do its work.
 *
 * <p>Carries three things, and {@code remedy} is <b>mandatory</b> -- a code check fails the build
 * on a blank one. A step that cannot tell the user what to do about its failure does not ship;
 * that is the whole difference between this and the Bash script it replaces.
 */
public class StepFailedException extends RuntimeException {
    private final String what;
    private final String why;
    private final String remedy;

    public StepFailedException(String what, String why, String remedy) {
        this(what, why, remedy, null);
    }

    public StepFailedException(String what, String why, String remedy, Throwable cause) {
        super(what + ": " + why, cause);
        if (remedy == null || remedy.isBlank()) {
            throw new IllegalArgumentException(
                    "Every step failure must carry a remedy. Offending failure: " + what);
        }
        this.what = what;
        this.why = why;
        this.remedy = remedy;
    }

    public String what()   { return this.what; }
    public String why()    { return this.why; }
    public String remedy() { return this.remedy; }
}
