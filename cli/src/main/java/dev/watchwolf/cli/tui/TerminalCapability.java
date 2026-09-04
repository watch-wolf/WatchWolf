package dev.watchwolf.cli.tui;

/**
 * Whether a full-screen interface can be opened at all.
 *
 * <p>Lanterna needs a real terminal and throws otherwise. Inside a pipe, a CI job, or
 * {@code docker run} without {@code -t} there is none, so both screens check here first and fall
 * back to flags and plain output with a message that says which to use.
 */
public final class TerminalCapability {

    private TerminalCapability() {
    }

    public static boolean available() {
        if (System.console() == null) return false;
        String term = System.getenv("TERM");
        return term != null && !term.isBlank() && !term.equals("dumb");
    }

    public static String whyUnavailable() {
        if (System.console() == null) {
            return "there is no terminal attached (output is redirected, or docker run was "
                    + "given no -t)";
        }
        String term = System.getenv("TERM");
        if (term == null || term.isBlank()) return "TERM is not set";
        if (term.equals("dumb")) return "TERM is 'dumb'";
        return "unknown";
    }
}
