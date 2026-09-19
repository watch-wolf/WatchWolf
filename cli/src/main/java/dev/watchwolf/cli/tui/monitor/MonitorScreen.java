package dev.watchwolf.cli.tui.monitor;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;

import dev.watchwolf.cli.bundle.BundleWriter;
import dev.watchwolf.cli.docker.DockerFacade;
import dev.watchwolf.cli.inventory.EnvironmentSnapshot;
import dev.watchwolf.cli.inventory.ManagerStatus;
import dev.watchwolf.cli.io.FileGateway;
import dev.watchwolf.cli.layout.InstallLayout;
import dev.watchwolf.cli.model.Confidence;
import dev.watchwolf.cli.progress.ProgressSink;
import dev.watchwolf.cli.tui.Painter;
import dev.watchwolf.cli.tui.Theme;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The live dashboard: btop's look and rhythm applied to WatchWolf's objects.
 *
 * <p><b>Two levels.</b> The overview is a pure inventory with no log pane at all, so its whole
 * height goes to the tree. {@code Enter} descends into one entity, where the facts sit on top and
 * its log fills everything below; {@code Esc} comes back. Logs belong to one thing, so they are
 * only shown once you have picked that thing.
 *
 * <p>Resource figures are a small number beside a container, never a graph -- this is a view of
 * servers and bots, not of the machine.
 */
public final class MonitorScreen implements AutoCloseable {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String SPINNER = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏";

    private final InstallLayout layout;
    private final DockerFacade docker;
    private final FileGateway files;
    private final MonitorPoller poller;
    private final BundleWriter bundleWriter;
    private final ProgressSink progress;
    private final Duration interval;

    /**
     * How often the loop wakes when nothing is happening. Bounds worst-case input latency for an
     * isolated keypress to roughly 2x this -- comfortably under the ~120ms a terminal UI needs to
     * feel immediate. It does NOT gate how fast a burst of keys is processed: see {@link #run()}.
     */
    private static final long IDLE_POLL_MILLIS = 20;

    private Screen screen;
    private MonitorModel model;
    private final LogRing logs = new LogRing(5000);
    private AutoCloseable logStream;
    private String followedKey;
    private String statusMessage;
    private long statusMessageSetAtMillis;
    private String filterBeingTyped;
    private long lastFileLogReloadAtMillis;

    public MonitorScreen(InstallLayout layout, DockerFacade docker, FileGateway files,
                         MonitorPoller poller, BundleWriter bundleWriter, ProgressSink progress,
                         Duration interval) {
        this.layout = layout;
        this.docker = docker;
        this.files = files;
        this.poller = poller;
        this.bundleWriter = bundleWriter;
        this.progress = progress;
        this.interval = interval;
    }

    public void run() throws IOException {
        Terminal terminal = new DefaultTerminalFactory().createTerminal();
        this.runOn(new TerminalScreen(terminal));
    }

    /**
     * Package-visible so a test can inject a {@link com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal}
     * -backed screen and drive the real loop -- including its actual input-draining and timing
     * behaviour -- with no pty involved. See {@code NFMonitorScreenResponsivenessShould}.
     */
    void runOn(Screen screen) throws IOException {
        this.screen = screen;
        this.screen.startScreen();
        this.screen.setCursorPosition(null);

        this.model = new MonitorModel(this.layout, this.poller.start());

        try {
            while (true) {
                EnvironmentSnapshot latest = this.poller.latest();
                if (latest != null) this.model.update(latest);

                // Drain EVERY key already buffered before drawing, not just one. A single
                // pollInput() per loop meant a quick burst -- someone tapping an arrow key a few
                // times to move the selection, which is completely normal -- only advanced by one
                // step per IDLE_POLL_MILLIS, so five taps could take five loop periods to catch up
                // and visibly finish moving. Draining fully means a burst is fully reflected in the
                // very next frame, however many keys it contained.
                boolean quit = false;
                KeyStroke key;
                while ((key = this.screen.pollInput()) != null) {
                    if (!this.handle(key)) {
                        quit = true;
                        break;
                    }
                }

                this.syncLogStream();
                this.draw();   // always AFTER handling input, so this frame shows its effect
                if (quit) return;

                sleep(IDLE_POLL_MILLIS);
            }
        } finally {
            this.closeLogStream();
            this.screen.stopScreen();
        }
    }

    // ---- input -----------------------------------------------------------------------------

    /** @return false to quit */
    private boolean handle(KeyStroke key) {
        if (this.filterBeingTyped != null) {
            return this.handleFilterInput(key);
        }

        if (key.getKeyType() == KeyType.Character) {
            switch (Character.toLowerCase(key.getCharacter())) {
                case 'q' -> {
                    if (this.model.isInEntityView()) {
                        this.leaveEntity();
                        return true;
                    }
                    return false;
                }
                case 'r' -> this.setStatusMessage("refreshed");
                case 'e' -> this.exportEverything();
                case 'f' -> {
                    // "following" means something will still arrive to follow -- for a stopped
                    // manager or a finished server nothing ever will again, so the key does
                    // nothing rather than claim a following state that can't be true. The footer
                    // hint and the log title omit "follow" entirely in that case (see draw()).
                    if (this.model.isInEntityView()
                            && this.model.entityView().map(EntityView::logIsLive).orElse(false)) {
                        this.logs.scrollToTail();
                        this.setStatusMessage("following");
                    }
                }
                case '/' -> {
                    if (this.model.isInEntityView()) this.filterBeingTyped = "";
                }
                case 's' -> {
                    if (this.model.isInEntityView()) this.saveCurrentLog();
                }
                case '?' -> this.setStatusMessage(this.helpText());
                default -> { }
            }
            return true;
        }

        switch (key.getKeyType()) {
            case ArrowDown -> {
                if (this.model.isInEntityView()) this.logs.scrollBack(-1);
                else this.model.moveDown();
            }
            case ArrowUp -> {
                if (this.model.isInEntityView()) this.logs.scrollBack(1);
                else this.model.moveUp();
            }
            case PageDown -> this.logs.scrollBack(-10);
            case PageUp -> this.logs.scrollBack(10);
            case ArrowRight, ArrowLeft -> {
                if (!this.model.isInEntityView()) this.model.toggleCollapse();
            }
            case Enter -> {
                if (!this.model.isInEntityView() && this.model.enter()) {
                    this.logs.clear();
                    this.statusMessage = null;
                }
            }
            case Escape -> {
                if (this.model.isInEntityView()) this.leaveEntity();
                else return false;
            }
            case EOF -> {
                return false;
            }
            default -> { }
        }
        return true;
    }

    private boolean handleFilterInput(KeyStroke key) {
        switch (key.getKeyType()) {
            case Character -> this.filterBeingTyped += key.getCharacter();
            case Backspace -> {
                if (!this.filterBeingTyped.isEmpty()) {
                    this.filterBeingTyped =
                            this.filterBeingTyped.substring(0, this.filterBeingTyped.length() - 1);
                }
            }
            case Enter -> {
                this.logs.setFilter(this.filterBeingTyped);
                this.filterBeingTyped = null;
            }
            case Escape -> this.filterBeingTyped = null;
            default -> { }
        }
        return true;
    }

    private void setStatusMessage(String message) {
        this.statusMessage = message;
        this.statusMessageSetAtMillis = System.currentTimeMillis();
    }

    private void leaveEntity() {
        this.model.back();
        this.closeLogStream();
        this.logs.clear();
        this.logs.setFilter("");
        this.followedKey = null;
    }

    // ---- log plumbing ----------------------------------------------------------------------

    /** Opens or switches the log stream when the focused entity changes. */
    private void syncLogStream() {
        if (!this.model.isInEntityView()) {
            this.closeLogStream();
            return;
        }

        EntityView view = this.model.entityView().orElse(null);
        if (view == null) return;

        String key = view.title();
        if (key.equals(this.followedKey)) {
            // a file log has no push notification, so re-read its tail periodically. Gated
            // on wall-clock time, not a frame count: the loop now runs far faster than any
            // sensible reload cadence, and coupling this to the loop's own polling rate would
            // either reload needlessly often or drift if that rate ever changes again.
            //
            // Only when logIsLive(): a finished server's file will never change again (the
            // container that would append to it is gone), so re-reading it on a timer is both
            // pointless and, before replaceAll() existed, was the exact mechanism that kept
            // yanking the view back to the tail -- see LogRing.replaceAll's Javadoc.
            if (view.logIsLive() && view.logSource() instanceof EntityView.LogSource.FileLog file
                    && System.currentTimeMillis() - this.lastFileLogReloadAtMillis >= 1000) {
                this.reloadFileLog(file.path());
                this.lastFileLogReloadAtMillis = System.currentTimeMillis();
            }
            return;
        }

        this.closeLogStream();
        this.logs.clear();
        this.followedKey = key;

        if (view.logSource() instanceof EntityView.LogSource.ContainerLog container) {
            this.logs.addAll(this.docker.logs(container.containerName(), 500));
            this.logStream = this.docker.followLogs(container.containerName(), 0, this.logs::add);

        } else if (view.logSource() instanceof EntityView.LogSource.FilteredContainerLog filtered) {
            // every bot writes to the one ClientsManager stream; the prefix is what separates them
            for (String line : this.docker.logs(filtered.containerName(), 2000)) {
                if (line.startsWith(filtered.linePrefix())) this.logs.add(line);
            }
            this.logStream = this.docker.followLogs(filtered.containerName(), 0, line -> {
                if (line.startsWith(filtered.linePrefix())) this.logs.add(line);
            });

        } else if (view.logSource() instanceof EntityView.LogSource.FileLog file) {
            this.reloadFileLog(file.path());
            // otherwise lastFileLogReloadAtMillis is left over from whatever was last viewed (or 0,
            // the first time), and the periodic branch above sees an already-elapsed interval and
            // reloads again on the very next loop tick instead of after a full cadence
            this.lastFileLogReloadAtMillis = System.currentTimeMillis();
        }
    }

    private void reloadFileLog(Path path) {
        try {
            List<String> tail = this.files.readLastLines(path, 1000);
            // replaceAll, not clear()+addAll(): this runs on every periodic re-read (a file log
            // has no push notification), and clear() resets scrollBack -- which used to snap the
            // view back to "following" and jump to the bottom every ~1s even mid-scroll. The
            // initial population (syncLogStream, on first entering the entity) already cleared
            // scrollBack to 0 itself, so this still starts at the tail there too.
            this.logs.replaceAll(tail);
        } catch (IOException ex) {
            this.logs.clear();
            this.logs.add("[could not read " + path + ": " + ex.getMessage() + "]");
        }
    }

    private void closeLogStream() {
        if (this.logStream == null) return;
        try {
            this.logStream.close();
        } catch (Exception ignored) {
            // the stream is being discarded anyway
        }
        this.logStream = null;
        this.followedKey = null;
    }

    // ---- actions ---------------------------------------------------------------------------

    private void exportEverything() {
        this.setStatusMessage("exporting all logs...");
        try {
            Path destination = this.layout.exportedLogsDir()
                    .resolve("watchwolf-logs-" + System.currentTimeMillis() + ".tar.gz");
            Path written = this.bundleWriter.write(destination,
                    BundleWriter.Selection.everything(), ProgressSink.discarding());
            this.setStatusMessage("exported everything to " + written);
        } catch (RuntimeException ex) {
            this.setStatusMessage("export failed: " + ex.getMessage());
        }
    }

    private void saveCurrentLog() {
        EntityView view = this.model.entityView().orElse(null);
        if (view == null) return;
        try {
            Path destination = this.layout.exportedLogsDir()
                    .resolve(view.title().replaceAll("[^A-Za-z0-9_.-]", "_") + ".log");
            this.files.writeString(destination, String.join("\n", this.logs.window(100000)) + "\n");
            this.setStatusMessage("saved " + destination);
        } catch (IOException ex) {
            this.setStatusMessage("could not save: " + ex.getMessage());
        }
    }

    private String helpText() {
        if (this.model.isInEntityView()) {
            boolean live = this.model.entityView().map(EntityView::logIsLive).orElse(false);
            return (live ? "f follow · " : "")
                    + "/ filter · PgUp/PgDn scroll · s save this log · e export all · Esc back";
        }
        return "Bots are threads inside the ClientsManager container, not containers, so their "
                + "ports are read from it and their names from its output.";
    }

    // ---- drawing ---------------------------------------------------------------------------

    private void draw() throws IOException {
        TerminalSize size = this.screen.doResizeIfNecessary();
        if (size == null) size = this.screen.getTerminalSize();

        Painter painter = new Painter(this.screen.newTextGraphics(), size);
        painter.clear();

        if (this.model.isInEntityView()) {
            this.drawEntityView(painter);
        } else {
            this.drawOverview(painter);
        }

        this.screen.refresh();
    }

    private void drawOverview(Painter painter) {
        int width = painter.width();
        int height = painter.height();
        EnvironmentSnapshot snapshot = this.model.snapshot();

        int managersWidth = Math.min(48, width / 2);
        int managersHeight = 6;

        this.drawManagersPanel(painter, 0, 0, managersWidth, managersHeight, snapshot);
        this.drawSummaryPanel(painter, managersWidth, 0, width - managersWidth,
                managersHeight, snapshot);

        // no log pane here on purpose: the overview is an inventory, so the tree gets the rest
        int treeTop = managersHeight;
        int treeHeight = height - treeTop - 1;
        this.drawChildrenPanel(painter, 0, treeTop, width, treeHeight);

        this.drawFooter(painter, height - 1,
                "↑↓ select   ⏎ enter   ←→ fold   e export all logs   r refresh   ? help   q quit");
    }

    private void drawManagersPanel(Painter painter, int x, int y, int width, int height,
                                   EnvironmentSnapshot snapshot) {
        painter.panel(x, y, width, height, "1 managers");

        int row = y + 1;
        for (ManagerStatus manager : snapshot.managers()) {
            String state = manager.stateLabel();
            painter.text(x + 2, row, manager.confidenceGlyph(), Theme.forState(state));
            painter.text(x + 4, row, Painter.fit(manager.name(), 17), Theme.TEXT);
            painter.text(x + 21, row, Painter.fit(state, 9), Theme.forState(state));
            painter.text(x + 30, row, ":" + manager.kind().portLabel(), Theme.DIM);
            row++;

            String resources = manager.stats()
                    .map(stats -> String.format("cpu %.1f%%  mem %s",
                            stats.cpuPercent(), stats.humanMemory()))
                    .orElse("cpu n/a   mem n/a");
            painter.text(x + 4, row, resources, Theme.DIM);
            row++;
        }
    }

    private void drawSummaryPanel(Painter painter, int x, int y, int width, int height,
                                  EnvironmentSnapshot snapshot) {
        String clock = CLOCK.format(snapshot.takenAt().atZone(ZoneId.systemDefault()));
        painter.panel(x, y, width, height, clock + "  ·  " + this.interval.toMillis() + "ms");

        int row = y + 1;
        painter.text(x + 2, row++, "install   " + this.layout.base(), Theme.DIM);
        painter.text(x + 2, row++, "docker    "
                + (snapshot.dockerReachable() ? snapshot.dockerVersion() : "unreachable"),
                snapshot.dockerReachable() ? Theme.DIM : Theme.BAD);
        painter.text(x + 2, row++, "children  " + snapshot.runningServers().size()
                + " server(s), " + snapshot.clients().clients().size() + " bot(s)", Theme.DIM);

        String advertised = "advertise " + (snapshot.advertisedAddress() == null
                ? "unknown" : snapshot.advertisedAddress());
        painter.text(x + 2, row, advertised
                        + (snapshot.hostNetworkingTruthful() ? "" : "  (container view)"),
                snapshot.hostNetworkingTruthful() ? Theme.DIM : Theme.WARN);
    }

    private void drawChildrenPanel(Painter painter, int x, int y, int width, int height) {
        painter.panel(x, y, width, height, "2 children");

        painter.text(x + 3, y + 1, String.format("%-28s %-8s %-8s %-12s %-9s %s",
                "NAME", "TYPE", "VERSION", "PORTS", "STATE", "UP"), Theme.HEADING);

        List<MonitorRow> rows = this.model.rows();
        int visible = height - 3;
        int firstRow = Math.max(0, Math.min(this.model.cursor() - visible / 2,
                Math.max(0, rows.size() - visible)));

        for (int i = 0; i < visible && (firstRow + i) < rows.size(); i++) {
            MonitorRow row = rows.get(firstRow + i);
            int screenRow = y + 2 + i;
            boolean selected = (firstRow + i) == this.model.cursor();

            if (row.kind() == MonitorRow.Kind.NOTE) {
                painter.text(x + 3, screenRow, "  " + row.name(), Theme.DIM);
                continue;
            }

            String line = String.format(" %s %-27s %-8s %-8s %-12s %-9s %s",
                    row.confidence() == Confidence.OBSERVED ? "●"
                            : row.confidence() == Confidence.INFERRED ? "◐" : "○",
                    Painter.fit(row.indentedName(), 27), Painter.fit(row.type(), 8),
                    Painter.fit(row.version(), 8), Painter.fit(row.ports(), 12),
                    Painter.fit(row.state(), 9), row.uptime());

            if (selected) {
                painter.row(x + 1, screenRow, width - 2, line,
                        Theme.TEXT, Theme.SELECTED_BACKGROUND);
            } else {
                painter.text(x + 1, screenRow, line, Theme.forState(row.state()));
            }
        }
    }

    private void drawEntityView(Painter painter) {
        EntityView view = this.model.entityView().orElse(null);
        if (view == null) {
            this.model.back();
            return;
        }

        int width = painter.width();
        int height = painter.height();
        int factsHeight = view.facts().size() + 2;

        painter.panel(0, 0, width, factsHeight, view.title() + "  ·  Esc: back");
        for (int i = 0; i < view.facts().size(); i++) {
            painter.text(2, 1 + i, view.facts().get(i), Theme.TEXT);
        }

        int logTop = factsHeight;
        int logHeight = height - logTop - 1;

        String title = "logs";
        if (!this.logs.filter().isEmpty()) title += "  ·  filter \"" + this.logs.filter() + "\"";
        // "following" implies more could still arrive -- for a stopped manager or a finished
        // server nothing ever will again, so the label (and the 'f' key, and the footer hint
        // below) are all withheld rather than claim a state that can't be true.
        if (view.logIsLive() && this.logs.isFollowing()) title += "  ·  following";
        else if (!view.logIsLive()) title += "  ·  finished (no more output)";
        painter.panel(0, logTop, width, logHeight, title);

        if (view.logSource() instanceof EntityView.LogSource.None none) {
            painter.text(2, logTop + 1, "No log available: " + none.why(), Theme.WARN);
            if (view.unavailableReason() != null) {
                painter.text(2, logTop + 2, view.unavailableReason(), Theme.DIM);
            }
        } else {
            List<String> window = this.logs.window(logHeight - 2);
            if (window.isEmpty() && view.logIsLive()) {
                String spinner = String.valueOf(
                        SPINNER.charAt((int) ((System.currentTimeMillis() / 100) % SPINNER.length())));
                painter.text(2, logTop + 1, spinner + " waiting for output...", Theme.DIM);
            } else if (window.isEmpty()) {
                painter.text(2, logTop + 1, "(empty log)", Theme.DIM);
            }
            for (int i = 0; i < window.size(); i++) {
                painter.text(2, logTop + 1 + i, window.get(i), Theme.TEXT);
            }
        }

        if (this.filterBeingTyped != null) {
            painter.row(0, height - 1, width, " filter: " + this.filterBeingTyped + "_",
                    Theme.TEXT, Theme.SELECTED_BACKGROUND);
        } else {
            String hint = (view.logIsLive() ? "f follow   " : "")
                    + "/ filter   PgUp/PgDn scroll   s save this log   e export all   Esc back";
            this.drawFooter(painter, height - 1, hint);
        }
    }

    private void drawFooter(Painter painter, int row, String keys) {
        if (this.statusMessage != null) {
            painter.text(1, row, this.statusMessage, Theme.WARN);
            if (System.currentTimeMillis() - this.statusMessageSetAtMillis >= 2000) {
                this.statusMessage = null;
            }
            return;
        }
        painter.text(1, row, keys, Theme.DIM);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** Package-visible for {@code NFMonitorScreenResponsivenessShould}. */
    MonitorModel modelForTesting() {
        return this.model;
    }

    /** Package-visible for {@code MonitorScreenLogViewingShould}. */
    LogRing logsForTesting() {
        return this.logs;
    }

    @Override
    public void close() {
        this.closeLogStream();
        this.poller.close();
    }
}
