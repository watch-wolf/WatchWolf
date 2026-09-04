package dev.watchwolf.cli.tui.install;

import dev.watchwolf.cli.step.StepOutcome;
import dev.watchwolf.cli.step.StepResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the install screen paints: the step list, the one operation currently running, and any
 * concurrent sub-operations (the Spigot builders).
 *
 * <p><b>Written from the worker thread, read from the UI thread</b>, so every method is
 * synchronized and every getter hands back a copied snapshot -- the draw loop must never iterate a
 * list the step runner is still appending to. That is the only concurrency in this class; there is
 * no terminal code in it, so all of the behaviour below is unit-testable without a screen.
 */
public final class InstallProgressModel {
    /** One row of the step list. */
    public record StepLine(String id, String title, StepOutcome outcome, boolean running) {
        public boolean finished() {
            return this.outcome != null;
        }
    }

    /** One concurrent sub-operation -- one Spigot version compiling, for instance. */
    public record Task(String id, String label, String detail, long done, long total,
                       boolean finished, boolean succeeded, String outcome, long startedAtMillis) {
        /** -1 when the length is genuinely unknown, so the screen draws a sweeping bar instead. */
        public double fraction() {
            if (this.total <= 0 || this.done < 0) return -1;
            return Math.max(0, Math.min(1, (double) this.done / this.total));
        }
    }

    /** Why the run stopped, once it has. */
    public enum Ending { COMPLETED, ABORTED, BACKGROUNDED }

    private final List<StepLine> steps = new ArrayList<>();
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    private int totalSteps;
    private int stepsFinished;
    private String currentOperation;
    private String currentDetail;
    private long currentDone = -1;
    private long currentTotal = -1;
    private List<StepResult> results = List.of();
    private Ending ending;

    // ---- written by the step runner / progress sink --------------------------------------------

    public synchronized void runStarting(int totalSteps) {
        this.totalSteps = totalSteps;
    }

    public synchronized void stepStarting(String id, String title) {
        this.steps.add(new StepLine(id, title, null, true));
    }

    public synchronized void stepFinished(String id, String title, StepOutcome outcome) {
        for (int i = 0; i < this.steps.size(); i++) {
            if (this.steps.get(i).id().equals(id)) {
                this.steps.set(i, new StepLine(id, title, outcome, false));
                this.stepsFinished++;
                this.clearCurrentOperation();
                return;
            }
        }
        // blocked steps never "start", so they arrive here unseen
        this.steps.add(new StepLine(id, title, outcome, false));
        this.stepsFinished++;
    }

    public synchronized void runFinished(List<StepResult> results, Ending ending) {
        this.results = List.copyOf(results);
        this.ending = ending;
        this.clearCurrentOperation();
        this.tasks.clear();
    }

    public synchronized void operationStarted(String what) {
        this.currentOperation = what;
        this.currentDetail = null;
        this.currentDone = -1;
        this.currentTotal = -1;
    }

    public synchronized void operationUpdated(String detail, long done, long total) {
        this.currentDetail = detail;
        this.currentDone = done;
        this.currentTotal = total;
    }

    public synchronized void operationEnded(String outcome) {
        this.clearCurrentOperation();
    }

    public synchronized void warn(String message) {
        this.warnings.add(message);
    }

    public synchronized void taskStarted(String id, String label, long startedAtMillis) {
        this.tasks.put(id, new Task(id, label, null, -1, -1, false, false, null, startedAtMillis));
    }

    public synchronized void taskUpdated(String id, String label, String detail,
                                         long done, long total) {
        Task existing = this.tasks.get(id);
        long startedAt = existing == null ? System.currentTimeMillis() : existing.startedAtMillis();
        this.tasks.put(id, new Task(id, label, detail, done, total, false, false, null, startedAt));
    }

    public synchronized void taskFinished(String id, String label, String outcome,
                                          boolean succeeded) {
        Task existing = this.tasks.get(id);
        long startedAt = existing == null ? System.currentTimeMillis() : existing.startedAtMillis();
        String detail = existing == null ? null : existing.detail();
        this.tasks.put(id,
                new Task(id, label, detail, -1, -1, true, succeeded, outcome, startedAt));
    }

    private void clearCurrentOperation() {
        this.currentOperation = null;
        this.currentDetail = null;
        this.currentDone = -1;
        this.currentTotal = -1;
    }

    // ---- read by the screen --------------------------------------------------------------------

    public synchronized List<StepLine> steps()            { return List.copyOf(this.steps); }
    public synchronized List<Task> tasks()                { return List.copyOf(this.tasks.values()); }
    public synchronized List<String> warnings()           { return List.copyOf(this.warnings); }
    public synchronized int totalSteps()                  { return this.totalSteps; }
    public synchronized int stepsFinished()               { return this.stepsFinished; }
    public synchronized List<StepResult> results()        { return this.results; }
    public synchronized Optional<Ending> ending()         { return Optional.ofNullable(this.ending); }
    public synchronized boolean isFinished()              { return this.ending != null; }

    public synchronized Optional<String> currentOperation() {
        return Optional.ofNullable(this.currentOperation);
    }

    public synchronized Optional<String> currentDetail() {
        return Optional.ofNullable(this.currentDetail);
    }

    /** -1 when the current operation has no known total (so: a sweeping bar, not a lie). */
    public synchronized double currentFraction() {
        if (this.currentTotal <= 0 || this.currentDone < 0) return -1;
        return Math.max(0, Math.min(1, (double) this.currentDone / this.currentTotal));
    }

    /** Overall progress across the whole run, for the header bar. */
    public synchronized double overallFraction() {
        if (this.totalSteps <= 0) return -1;
        return Math.max(0, Math.min(1, (double) this.stepsFinished / this.totalSteps));
    }

    /**
     * Reads the step lines, not just {@link #results}: those only arrive when the whole run ends,
     * and the header bar has to turn red the moment something fails, not an hour later.
     */
    public synchronized boolean anythingFailed() {
        return this.steps.stream()
                .anyMatch(step -> step.outcome() != null && step.outcome().isFailure());
    }

    /** One line for the terminal once the screen is gone, e.g. "install successful". */
    public synchronized String summaryLine() {
        Ending how = this.ending;
        if (how == Ending.ABORTED) return "install aborted";
        if (how == Ending.BACKGROUNDED) return "install still running in the background";

        long failed = this.results.stream().filter(r -> r.outcome().isFailure()).count();
        if (failed == 0) return "install successful";
        return "install failed: " + failed + " step(s) of " + this.results.size();
    }
}
