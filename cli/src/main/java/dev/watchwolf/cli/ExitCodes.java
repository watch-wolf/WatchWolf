package dev.watchwolf.cli;

/**
 * What the process returns, and what the launcher does about it.
 *
 * <p>{@link #HOST_ACTION_REQUIRED} is the interesting one: an unprivileged container cannot write
 * {@code /etc} or drive systemd, so the CLI renders those commands into
 * {@code <base>/.watchwolf/host-action.sh} and exits 10. The launcher prints that script in full,
 * asks, and runs it. Every root command the user will run is on their screen first.
 */
public final class ExitCodes {
    public static final int OK = 0;
    public static final int ERROR = 1;
    public static final int USAGE = 2;
    public static final int DOCTOR_FAILED = 3;
    public static final int NOT_RUNNING = 4;
    public static final int HOST_ACTION_REQUIRED = 10;

    private ExitCodes() {
    }
}
