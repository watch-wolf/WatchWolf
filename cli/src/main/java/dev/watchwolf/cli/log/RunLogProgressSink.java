package dev.watchwolf.cli.log;

import dev.watchwolf.cli.progress.ProgressSink;

/**
 * Tees a {@link ProgressSink} into the {@link RunLog}: the wrapped sink still renders however it
 * renders, and the same events are written down.
 *
 * <p>What it deliberately does <b>not</b> log is {@link #update} and {@link #taskUpdate} -- the
 * per-second heartbeats. They are the whole point on screen (a stalled operation has to look
 * stalled) and pure noise in a file, where three hours of Spigot builds would be ten thousand
 * lines of "12m elapsed" around the twenty lines somebody actually needs.
 *
 * <p>{@link #detail} is logged even when the wrapped sink drops it for want of {@code --verbose}:
 * the file is read after something went wrong, when the detail is exactly what was missing.
 */
public final class RunLogProgressSink implements ProgressSink {
    private final ProgressSink delegate;
    private final RunLog log;

    public RunLogProgressSink(ProgressSink delegate, RunLog log) {
        this.delegate = delegate;
        this.log = log;
    }

    @Override
    public void begin(String what) {
        this.log.line("[v] " + what + "...");
        this.delegate.begin(what);
    }

    @Override
    public void update(String detail, long done, long total) {
        this.delegate.update(detail, done, total);
    }

    @Override
    public void end(String outcome) {
        this.log.line("[v]   ..." + (outcome == null ? "done" : outcome));
        this.delegate.end(outcome);
    }

    @Override
    public void warn(String message) {
        this.log.line("[w] " + message);
        this.delegate.warn(message);
    }

    @Override
    public void detail(String message) {
        this.log.line("[v]   " + message);
        this.delegate.detail(message);
    }

    @Override
    public void taskQueued(String id, String label) {
        this.log.line("[v]   " + label + ": queued");
        this.delegate.taskQueued(id, label);
    }

    @Override
    public void taskStarted(String id, String label) {
        this.log.line("[v]   " + label + ": started");
        this.delegate.taskStarted(id, label);
    }

    @Override
    public void taskUpdate(String id, String label, String detail, long done, long total) {
        this.delegate.taskUpdate(id, label, detail, done, total);
    }

    @Override
    public void taskFinished(String id, String label, String outcome, boolean succeeded) {
        this.log.line((succeeded ? "[v]   " : "[e]   ") + label + ": " + outcome);
        this.delegate.taskFinished(id, label, outcome, succeeded);
    }
}
