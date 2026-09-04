package dev.watchwolf.cli.docker;

/**
 * What a privileged one-shot helper container needs to run as the invoking user's image, and to
 * hand its output back owned by the invoking user.
 *
 * <p>Sourced from the launcher's own env vars ({@code WW_IMAGE}, {@code WW_UID}, {@code WW_GID}) --
 * reading them here, at one named call site, rather than inline wherever a helper is needed keeps
 * the environment coupling visible and lets a test hand in fixed values instead of mutating the
 * process environment.
 *
 * @see dev.watchwolf.cli.command.InternalCopyCommand the helper's own body
 */
public record RootHelperConfig(String image, String uid, String gid) {

    public static RootHelperConfig fromEnvironment() {
        return new RootHelperConfig(
                System.getenv("WW_IMAGE"), System.getenv("WW_UID"), System.getenv("WW_GID"));
    }

    /** False when not run through the launcher (a bare `docker run`, or a unit test). */
    public boolean isAvailable() {
        return this.image != null && this.uid != null && this.gid != null;
    }
}
