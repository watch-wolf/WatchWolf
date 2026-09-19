package dev.watchwolf.cli.progress;

import java.io.PrintStream;
import java.time.Duration;

/**
 * Renders progress to a stream using the {@code [v]/[w]/[e]/[i]} prefixes every other WatchWolf
 * script uses, so the CLI's output reads continuously with them.
 *
 * <pre>
 * [v] Cloning WatchWolf-ServersManager from github.com/rogermiranda1000 (branch dev)...
 * [v]   ...done (4.2s, 3812 objects)
 * [v] Pulling eclipse-temurin:21-jdk from docker.io... 142MB/319MB (44%)
 * [w] watchwolf.dev did not answer in 10s; retrying (2/3)
 * </pre>
 */
public final class PlainProgressSink implements ProgressSink {
    private final PrintStream out;
    private final PrintStream err;
    private final boolean verbose;
    private final boolean interactive;

    private String current;
    private long startedAtNanos;
    private boolean wroteInlineUpdate;

    public PlainProgressSink(PrintStream out, PrintStream err, boolean verbose, boolean interactive) {
        this.out = out;
        this.err = err;
        this.verbose = verbose;
        this.interactive = interactive;
    }

    public static PlainProgressSink toStdout(boolean verbose) {
        return new PlainProgressSink(System.out, System.err, verbose, System.console() != null);
    }

    @Override
    public synchronized void begin(String what) {
        this.finishInlineLine();
        this.current = what;
        this.startedAtNanos = System.nanoTime();
        this.wroteInlineUpdate = false;
        this.out.println("[v] " + what + "...");
        this.out.flush();
    }

    @Override
    public synchronized void update(String detail, long done, long total) {
        if (detail == null || detail.isBlank()) return;

        String line = "[v]   " + detail;
        if (total > 0 && done >= 0) {
            line += " (" + Math.round((done * 100.0) / total) + "%)";
        }

        if (this.interactive) {
            // rewrite one line in place, the way the old script's spinner did
            this.out.print("\r" + line + "    ");
            this.wroteInlineUpdate = true;
        } else {
            this.out.println(line);
        }
        this.out.flush();
    }

    @Override
    public synchronized void end(String outcome) {
        this.finishInlineLine();
        String elapsed = this.current == null ? "" : " (" + humanDuration(this.startedAtNanos) + ")";
        this.out.println("[v]   ..." + (outcome == null ? "done" : outcome) + elapsed);
        this.out.flush();
        this.current = null;
    }

    @Override
    public synchronized void warn(String message) {
        this.finishInlineLine();
        this.err.println("[w] " + message);
        this.err.flush();
    }

    @Override
    public synchronized void detail(String message) {
        if (!this.verbose) return;
        this.finishInlineLine();
        this.out.println("[v]   " + message);
        this.out.flush();
    }

    private void finishInlineLine() {
        if (this.wroteInlineUpdate) {
            this.out.println();
            this.wroteInlineUpdate = false;
        }
    }

    private static String humanDuration(long startNanos) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        long seconds = elapsed.toSeconds();
        if (seconds < 60) return String.format("%.1fs", elapsed.toMillis() / 1000.0);
        if (seconds < 3600) return (seconds / 60) + "m" + (seconds % 60) + "s";
        return (seconds / 3600) + "h" + ((seconds % 3600) / 60) + "m";
    }
}
