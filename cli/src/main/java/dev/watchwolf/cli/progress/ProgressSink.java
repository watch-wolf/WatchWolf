package dev.watchwolf.cli.progress;

/**
 * Where a step reports what it is doing.
 *
 * <p>The old installer's worst trait is going quiet for minutes: a slow clone and a hung one look
 * identical. So <b>every operation that touches the network, the Docker daemon, or the disk in bulk
 * announces itself before it starts, names the remote host it is waiting on, and says when it
 * finished.</b> That is a requirement on the step contract, not a nicety -- steps never call
 * {@code System.out} directly, and a code check enforces it.
 *
 * <p>Three implementations render the same events: {@link PlainProgressSink} to stdout,
 * a TUI sink to a live line in the menu or monitor, and a JSON sink for {@code --json}.
 */
public interface ProgressSink {
    /**
     * Something slow is starting.
     *
     * @param what present tense and specific, naming the remote when there is one --
     *             {@code "Cloning WatchWolf-ServersManager from github.com/rogermiranda1000 (branch dev)"}.
     *             "Downloading usual plugins" is useless when api.spiget.org is what is down.
     */
    void begin(String what);

    /**
     * Progress within the current operation. Called at least every couple of seconds for anything
     * long, so a stalled call is visibly stalled rather than indistinguishable from a slow one.
     *
     * @param detail  free text, e.g. {@code "142MB/319MB"} or {@code "12m elapsed"}
     * @param done    completed units, or -1 when unknown
     * @param total   total units, or -1 when unknown
     */
    void update(String detail, long done, long total);

    /** The current operation finished. */
    void end(String outcome);

    /** A non-fatal problem worth telling the user about, e.g. a retry. */
    void warn(String message);

    /** Detail only wanted under {@code --verbose}. */
    void detail(String message);

    // ---- concurrent sub-operations ------------------------------------------------------------
    //
    // begin/update/end describe ONE thing happening at a time, which is all most steps need. The
    // Spigot builders are the exception: several versions compile at once, each in its own
    // container for about an hour, and an aggregate "2/5 done" hides which one is stuck. These
    // four report them individually, so a TUI can draw a row per jar the way `docker pull` draws
    // one per layer. They default to silence or a plain detail line, so a stream-based sink needs
    // no changes.

    /**
     * One of several concurrent sub-operations is going to happen, but has not begun -- there are
     * more of them than the parallelism allows, so it is queued behind the ones running.
     *
     * <p>Announced up front rather than when it starts, so the whole of what was asked for is
     * visible from the first frame: five versions with two builders is five rows, three of them
     * waiting, not two rows that grow into five over the next three hours.
     */
    default void taskQueued(String id, String label) {
        // silent by default: a stream sink already said how many versions were selected
    }

    /** One of several concurrent sub-operations started. {@code id} is opaque and stable. */
    default void taskStarted(String id, String label) {
        this.detail(label + ": started");
    }

    /** @param done/total  units for a real bar, or -1 when the length is genuinely unknowable */
    default void taskUpdate(String id, String label, String detail, long done, long total) {
        // deliberately silent by default: this is called on every poll, and a stream sink that
        // echoed it would bury the one-line-per-event output the plain path is built around
    }

    default void taskFinished(String id, String label, String outcome, boolean succeeded) {
        this.detail(label + ": " + outcome);
    }

    /** A sink that discards everything -- for tests and for non-reporting call paths. */
    static ProgressSink discarding() {
        return new ProgressSink() {
            @Override public void begin(String what) { }
            @Override public void update(String detail, long done, long total) { }
            @Override public void end(String outcome) { }
            @Override public void warn(String message) { }
            @Override public void detail(String message) { }
            @Override public void taskQueued(String id, String label) { }
            @Override public void taskStarted(String id, String label) { }
            @Override public void taskFinished(String id, String label, String outcome,
                                               boolean succeeded) { }
        };
    }
}
