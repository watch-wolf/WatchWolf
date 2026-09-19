package dev.watchwolf.cli.model;

/**
 * How sure the CLI is about a row it is showing.
 *
 * <p>This exists because client bots are not Docker objects: they are Python threads inside the
 * single {@code ClientsManager} container, so some of what the monitor shows is read off a log
 * rather than observed. Carrying the distinction into {@code status --json} and the diagnostics
 * bundle means a bug report says which rows were guessed, instead of presenting an inference as a
 * fact.
 */
public enum Confidence {
    /** Directly observed -- a container Docker listed, or a socket seen listening. */
    OBSERVED("●"),
    /** Inferred from a log or a filename; plausible, not proven. */
    INFERRED("◐"),
    /** Known to exist but nothing more could be established. */
    UNKNOWN("○");

    private final String glyph;

    Confidence(String glyph) {
        this.glyph = glyph;
    }

    public String glyph() {
        return this.glyph;
    }
}
