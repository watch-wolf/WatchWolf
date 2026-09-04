package dev.watchwolf.cli.tui.install;

import dev.watchwolf.cli.progress.ProgressSink;

/**
 * Routes a step's progress into {@link InstallProgressModel} instead of stdout, so the install
 * looks like the rest of the UI when the user came from the menu.
 *
 * <p>Nothing here prints: writing to {@code System.out} underneath a Lanterna screen corrupts the
 * frame, which is exactly why the plain sink cannot just be reused.
 */
public final class TuiProgressSink implements ProgressSink {
    private final InstallProgressModel model;

    public TuiProgressSink(InstallProgressModel model) {
        this.model = model;
    }

    @Override
    public void begin(String what) {
        this.model.operationStarted(what);
    }

    @Override
    public void update(String detail, long done, long total) {
        this.model.operationUpdated(detail, done, total);
    }

    @Override
    public void end(String outcome) {
        this.model.operationEnded(outcome);
    }

    @Override
    public void warn(String message) {
        this.model.warn(message);
    }

    @Override
    public void detail(String message) {
        // --verbose chatter has nowhere useful to go on a fixed-height screen; the operation line
        // and the per-task rows already carry what a watching user needs
    }

    @Override
    public void taskQueued(String id, String label) {
        this.model.taskQueued(id, label);
    }

    @Override
    public void taskStarted(String id, String label) {
        this.model.taskStarted(id, label, System.currentTimeMillis());
    }

    @Override
    public void taskUpdate(String id, String label, String detail, long done, long total) {
        this.model.taskUpdated(id, label, detail, done, total);
    }

    @Override
    public void taskFinished(String id, String label, String outcome, boolean succeeded) {
        this.model.taskFinished(id, label, outcome, succeeded);
    }
}
