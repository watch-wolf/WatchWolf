package dev.watchwolf.cli.tui.install;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;

import dev.watchwolf.cli.tui.Painter;
import dev.watchwolf.cli.tui.Theme;

import java.io.IOException;
import java.util.List;

/**
 * The install itself, drawn rather than printed: coming from the menuconfig screen and then being
 * dropped back to an hour of scrolling {@code [v]} lines is a jarring handover, and it buries the
 * one thing that actually needs watching -- which Spigot version is still compiling.
 *
 * <p>Three things it does that the plain output cannot:
 *
 * <ul>
 *   <li><b>A bar per concurrent jar.</b> BuildTools runs several versions at once, each for about
 *       an hour; an aggregate "2/5 done" cannot say which one is stuck. Each gets its own row, the
 *       way {@code docker pull} gives each layer one. Lengths that are genuinely unknown get a
 *       sweeping bar rather than an invented percentage.</li>
 *   <li><b>Abort, behind a confirmation.</b> An hour into a build, one stray key must not throw the
 *       run away -- so {@code q} asks first. Aborting stops at the next step boundary; nothing
 *       already done is undone, and re-running resumes.</li>
 *   <li><b>Send to the background.</b> The long part is Spigot, and those builders are detached
 *       containers that outlive this process, so the run can be handed off and picked up later.</li>
 * </ul>
 *
 * <p>The screen owns no state of the run: it paints {@link InstallProgressModel}, which the worker
 * thread writes. All it decides is how the run <em>ends</em>, which is why {@link #runOn} returns an
 * {@link InstallProgressModel.Ending} and the caller -- not this class -- calls
 * {@link InstallProgressModel#runFinished}.
 */
public final class InstallProgressScreen {
    private static final String SPINNER = "|/-\\";

    /** Fast enough that the spinner and the sweeping bars move smoothly, cheap because drawing is. */
    private static final long FRAME_MILLIS = 90;

    /** Wide enough for {@code [PERFORMED BUT UNVERIFIED]}'s short form; see {@link #stepMarker}. */
    private static final int MARKER_WIDTH = 12;

    private enum Overlay { NONE, CONFIRM_ABORT, BACKGROUND_NOTICE }

    private final InstallProgressModel model;

    private Screen screen;
    private Overlay overlay = Overlay.NONE;
    private InstallProgressModel.Ending decision;
    private boolean acknowledged;

    public InstallProgressScreen(InstallProgressModel model) {
        this.model = model;
    }

    /** @return how the run ended: on its own, aborted, or handed to the background */
    public InstallProgressModel.Ending run() throws IOException {
        return this.runOn(new TerminalScreen(new DefaultTerminalFactory().createTerminal()));
    }

    /**
     * Package-visible so a test can drive the real loop over a
     * {@link com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal}-backed screen, with no
     * pty involved -- the same pattern the menu and monitor screens are tested with.
     */
    InstallProgressModel.Ending runOn(Screen screen) throws IOException {
        this.screen = screen;
        this.screen.startScreen();
        this.screen.setCursorPosition(null);

        try {
            while (true) {
                // drain every buffered key before drawing, so a burst lands in the very next frame
                KeyStroke key;
                while ((key = this.screen.pollInput()) != null) {
                    this.handle(key);
                    if (this.decision != null) return this.decision;
                }

                if (this.acknowledged) {
                    // the run ended by itself and the user has seen the summary
                    return this.model.ending().orElse(InstallProgressModel.Ending.COMPLETED);
                }

                this.draw();
                sleep(FRAME_MILLIS);
            }
        } finally {
            this.screen.stopScreen();
        }
    }

    // ---- input ---------------------------------------------------------------------------------

    private void handle(KeyStroke key) {
        boolean enter = key.getKeyType() == KeyType.Enter;
        boolean escape = key.getKeyType() == KeyType.Escape;
        char character = key.getKeyType() == KeyType.Character
                ? Character.toLowerCase(key.getCharacter()) : '\0';

        if (this.model.isFinished()) {
            // nothing left to abort or to background: the summary stays up until acknowledged, so
            // an unattended install cannot scroll its own result away
            this.acknowledged = true;
            return;
        }

        switch (this.overlay) {
            case CONFIRM_ABORT -> {
                if (character == 'y') this.decision = InstallProgressModel.Ending.ABORTED;
                else if (character == 'n' || escape) this.overlay = Overlay.NONE;
            }
            case BACKGROUND_NOTICE -> {
                if (enter || character == ' ' || character == 'o') {
                    this.decision = InstallProgressModel.Ending.BACKGROUNDED;
                } else if (escape) {
                    this.overlay = Overlay.NONE;
                }
            }
            case NONE -> {
                // never on the first press -- see the class Javadoc
                if (character == 'q' || escape) this.overlay = Overlay.CONFIRM_ABORT;
                else if (character == 'b') this.overlay = Overlay.BACKGROUND_NOTICE;
            }
        }
    }

    // ---- drawing -------------------------------------------------------------------------------

    private void draw() throws IOException {
        TerminalSize size = this.screen.doResizeIfNecessary();
        if (size == null) size = this.screen.getTerminalSize();

        Painter painter = new Painter(this.screen.newTextGraphics(), size);
        painter.clear();

        int width = painter.width();
        int height = painter.height();
        painter.panel(0, 0, width, height, this.model.isFinished()
                ? "WatchWolf install -- " + this.model.summaryLine() : "Installing WatchWolf");

        int row = this.drawOverall(painter, width, 2);
        row = this.drawSteps(painter, width, height, row);
        row = this.drawCurrentOperation(painter, width, height, row);
        this.drawTasks(painter, width, height, row);

        painter.divider(0, height - 3, width);
        painter.text(2, height - 2, this.footerHint(), Theme.DIM);

        switch (this.overlay) {
            case CONFIRM_ABORT -> this.drawModal(painter, width, height, "Abort the install?",
                    List.of("Everything finished so far is kept, and re-running resumes",
                            "from here -- nothing already done is undone.",
                            "",
                            "Spigot builders already started keep going in their own",
                            "containers either way.",
                            "",
                            "  y  abort        n  keep installing"), Theme.BAD);
            case BACKGROUND_NOTICE -> this.drawModal(painter, width, height,
                    "Keep installing in the background?", List.of(
                            "The install carries on without this window, and you get",
                            "your prompt back.",
                            "",
                            "Next time you run 'watchwolf build' it opens with how this",
                            "run ended, and an < OK > to acknowledge it, before the menu.",
                            "",
                            "  < OK >          esc  stay here"), Theme.WARN);
            case NONE -> { }
        }

        this.screen.refresh();
    }

    private int drawOverall(Painter painter, int width, int row) {
        int done = this.model.stepsFinished();
        int total = this.model.totalSteps();
        String counter = total > 0 ? done + "/" + total + " steps" : "starting...";

        int barWidth = Math.max(10, Math.min(40, width - counter.length() - 8));
        painter.bar(2, row, barWidth, Math.max(0, this.model.overallFraction()),
                this.model.anythingFailed() ? Theme.BAD : Theme.OK);
        painter.text(2 + barWidth + 2, row, counter, Theme.TEXT);
        return row + 2;
    }

    private int drawSteps(Painter painter, int width, int height, int row) {
        // keep the tail: what matters is the step running now and whatever just failed
        List<InstallProgressModel.StepLine> steps = this.model.steps();
        int room = Math.max(3, (height - 12) / 2);
        int from = Math.max(0, steps.size() - room);

        for (int i = from; i < steps.size() && row < height - 4; i++) {
            InstallProgressModel.StepLine step = steps.get(i);
            painter.text(2, row, Painter.fit(this.stepMarker(step), MARKER_WIDTH),
                    this.stepColour(step));
            painter.text(2 + MARKER_WIDTH, row,
                    Painter.fit(step.title(), Math.max(0, width - MARKER_WIDTH - 4)),
                    step.running() ? Theme.TEXT : Theme.DIM);
            row++;
        }
        return row + 1;
    }

    private int drawCurrentOperation(Painter painter, int width, int height, int row) {
        String operation = this.model.currentOperation().orElse(null);
        if (operation == null || row >= height - 5) return row;

        painter.text(2, row, this.spinner() + " " + Painter.fit(operation, Math.max(0, width - 6)),
                Theme.TEXT);
        row++;

        String detail = this.model.currentDetail().orElse(null);
        double fraction = this.model.currentFraction();
        int barWidth = Math.max(10, Math.min(30, width - 30));
        if (fraction >= 0) {
            painter.bar(4, row, barWidth, fraction, Theme.OK);
        } else {
            painter.sweepingBar(4, row, barWidth, phase(), Theme.OK);
        }
        if (detail != null) painter.text(4 + barWidth + 2, row, detail, Theme.DIM);
        return row + 2;
    }

    private void drawTasks(Painter painter, int width, int height, int row) {
        List<InstallProgressModel.Task> tasks = this.model.tasks();
        if (tasks.isEmpty() || row >= height - 4) return;

        painter.text(2, row++, "Building in parallel:", Theme.HEADING);

        int labelWidth = Math.min(24,
                tasks.stream().mapToInt(task -> task.label().length()).max().orElse(12));

        for (InstallProgressModel.Task task : tasks) {
            if (row >= height - 4) return;

            painter.text(4, row, Painter.fit(task.label(), labelWidth), Theme.TEXT);
            int barX = 4 + labelWidth + 2;
            int barWidth = Math.max(10, Math.min(24, width - barX - 24));

            if (task.finished()) {
                painter.text(barX, row, (task.succeeded() ? "[ok] " : "[FAILED] ") + task.outcome(),
                        task.succeeded() ? Theme.OK : Theme.BAD);
            } else {
                painter.text(barX, row, this.spinner(), Theme.WARN);
                double fraction = task.fraction();
                if (fraction >= 0) painter.bar(barX + 2, row, barWidth, fraction, Theme.OK);
                else painter.sweepingBar(barX + 2, row, barWidth, phase(), Theme.WARN);

                String detail = task.detail() == null
                        ? humanElapsed(System.currentTimeMillis() - task.startedAtMillis())
                        : task.detail();
                painter.text(barX + 2 + barWidth + 2, row, detail, Theme.DIM);
            }
            row++;
        }
    }

    private void drawModal(Painter painter, int width, int height, String title, List<String> lines,
                           TextColor accent) {
        int boxWidth = Math.min(width - 6, 64);
        int boxHeight = lines.size() + 4;
        int x = Math.max(0, (width - boxWidth) / 2);
        int y = Math.max(1, (height - boxHeight) / 2);

        // paint the hole first: the modal must not read as if it were part of the log behind it
        for (int i = 0; i < boxHeight; i++) {
            painter.row(x, y + i, boxWidth, "", Theme.TEXT, Theme.BACKGROUND);
        }
        painter.panel(x, y, boxWidth, boxHeight, null);
        painter.text(x + 2, y, " " + title + " ", accent);

        int row = y + 2;
        for (String line : lines) {
            painter.text(x + 3, row++, Painter.fit(line, boxWidth - 6), Theme.TEXT);
        }
    }

    private String footerHint() {
        if (this.model.isFinished()) return "press any key to close";
        return "q abort  .  b keep it running in the background";
    }

    private String stepMarker(InstallProgressModel.StepLine step) {
        if (step.running()) return this.spinner();
        if (step.outcome() == null) return "";
        return switch (step.outcome()) {
            case OK -> "[ok]";
            case ALREADY_DONE -> "[done]";
            case SKIPPED -> "[skipped]";
            case BLOCKED -> "[blocked]";
            case FAILED -> "[FAILED]";
            // the full label ("PERFORMED BUT UNVERIFIED") does not fit; the summary spells it out
            case VERIFY_FAILED -> "[UNVERIFIED]";
        };
    }

    private TextColor stepColour(InstallProgressModel.StepLine step) {
        if (step.running()) return Theme.WARN;
        if (step.outcome() == null) return Theme.DIM;
        if (step.outcome().isFailure()) return Theme.BAD;
        return step.outcome().satisfied() ? Theme.OK : Theme.DIM;
    }

    private String spinner() {
        return String.valueOf(SPINNER.charAt((int) (phase() % SPINNER.length())));
    }

    /** Frames since the epoch -- what both the spinner and the sweeping bars advance on. */
    private static long phase() {
        return System.currentTimeMillis() / FRAME_MILLIS;
    }

    private static String humanElapsed(long millis) {
        long seconds = Math.max(0, millis / 1000);
        if (seconds < 60) return seconds + "s elapsed";
        if (seconds < 3600) return (seconds / 60) + "m elapsed";
        return (seconds / 3600) + "h" + ((seconds % 3600) / 60) + "m elapsed";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
