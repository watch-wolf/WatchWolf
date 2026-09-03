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

    /** A sink that discards everything -- for tests and for non-reporting call paths. */
    static ProgressSink discarding() {
        return new ProgressSink() {
            @Override public void begin(String what) { }
            @Override public void update(String detail, long done, long total) { }
            @Override public void end(String outcome) { }
            @Override public void warn(String message) { }
            @Override public void detail(String message) { }
        };
    }
}
