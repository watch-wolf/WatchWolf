package dev.watchwolf.cli.tui.menu;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;

import dev.watchwolf.cli.model.BuildPlan;
import dev.watchwolf.cli.model.McVersion;
import dev.watchwolf.cli.remote.WatchWolfWebClient;
import dev.watchwolf.cli.tui.Async;
import dev.watchwolf.cli.tui.Painter;
import dev.watchwolf.cli.tui.Theme;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * The kernel-{@code menuconfig}-style install screen.
 *
 * <p>Space toggles, arrows navigate, Enter descends into a submenu, Escape goes back, {@code ?}
 * shows help for the highlighted row, and <b>F10 selects all / F9 deselects all</b> within the
 * focused list -- hinted in that list's own footer. There is deliberately no {@code < All >} row
 * anywhere: pseudo-entries in a checkbox list read as options and get mis-clicked.
 *
 * <p>It never freezes on the network. The version lists arrive as {@link Async} values fetched on a
 * worker thread; the screen paints a spinner naming the host it is waiting on, lists whatever is
 * already on disk immediately, and stays navigable throughout.
 */
public final class MenuConfigScreen implements AutoCloseable {
    private static final String SPINNER = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏";

    private final MenuModel model;
    private final VersionFetcher versionFetcher;

    /** Same reasoning as MonitorScreen's IDLE_POLL_MILLIS -- see there. */
    private static final long IDLE_POLL_MILLIS = 20;

    private Screen screen;
    private final Deque<MenuNode> path = new ArrayDeque<>();
    private int cursor;
    private String editingId;
    private String editBuffer;
    private boolean showHelp = true;
    private boolean cancelled;

    /** Supplies the remote lists, each on a thread of its own. */
    public interface VersionFetcher {
        void fetchSpigot(java.util.function.Consumer<Async<List<McVersion>>> onState);

        void fetchPaper(java.util.function.Consumer<Async<List<McVersion>>> onState);

        void fetchUsualPlugins(
                java.util.function.Consumer<Async<List<WatchWolfWebClient.UsualPlugin>>> onState);

        void cancel();
    }

    public MenuConfigScreen(MenuModel model, VersionFetcher versionFetcher) {
        this.model = model;
        this.versionFetcher = versionFetcher;
        this.path.push(model.root());
    }

    /** @return the plan the user accepted, or empty when they cancelled */
    public Optional<BuildPlan> run() throws IOException {
        return this.runOn(new TerminalScreen(new DefaultTerminalFactory().createTerminal()));
    }

    /**
     * Package-visible so a test can drive the real loop over a
     * {@link com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal}-backed screen, with
     * no pty involved. See {@code NFMenuConfigScreenResponsivenessShould}.
     */
    Optional<BuildPlan> runOn(Screen screen) throws IOException {
        this.screen = screen;
        this.screen.startScreen();
        this.screen.setCursorPosition(null);

        // prefetch both lists as soon as the screen opens, so descending into "Server jars" is
        // usually instant rather than a wait
        this.startFetches();

        try {
            while (true) {
                // Drain every buffered key before drawing, not just one -- a single pollInput()
                // per loop meant a quick burst of taps (completely normal when navigating a list)
                // only advanced by one step per IDLE_POLL_MILLIS, so a handful of presses could
                // take several loop periods to visibly finish. Draining fully means a burst is
                // fully reflected in the very next frame, however many keys it contained.
                KeyStroke key;
                while ((key = this.screen.pollInput()) != null) {
                    Decision decision = this.handle(key);
                    if (decision == Decision.START) {
                        this.draw();
                        return Optional.of(this.model.toBuildPlan());
                    }
                    if (decision == Decision.CANCEL) {
                        this.draw();
                        return Optional.empty();
                    }
                }

                this.draw();   // always AFTER handling input, so this frame shows its effect
                sleep(IDLE_POLL_MILLIS);
            }
        } finally {
            this.screen.stopScreen();
        }
    }

    private enum Decision { CONTINUE, START, CANCEL }

    private void startFetches() {
        this.model.spigotLoading(Instant.now());
        this.model.paperLoading(Instant.now());
        this.model.usualPluginsLoading(Instant.now());

        this.versionFetcher.fetchSpigot(state -> {
            if (state.isLoaded()) {
                this.model.spigotLoaded(state.value().orElse(List.of()), 0);
            } else if (state.hasFailed()) {
                this.model.spigotFailed(state.failureDetail().orElse("unknown"),
                        state.failureRemedy().orElse(null));
            }
        });
        this.versionFetcher.fetchPaper(state -> {
            if (state.isLoaded()) {
                this.model.paperLoaded(state.value().orElse(List.of()), 0);
            } else if (state.hasFailed()) {
                this.model.paperFailed(state.failureDetail().orElse("unknown"),
                        state.failureRemedy().orElse(null));
            }
        });
        this.versionFetcher.fetchUsualPlugins(state -> {
            if (state.isLoaded()) {
                this.model.usualPluginsLoaded(state.value().orElse(List.of()));
            } else if (state.hasFailed()) {
                this.model.usualPluginsFailed(state.failureDetail().orElse("unknown"),
                        state.failureRemedy().orElse(null));
            }
        });
    }

    // ---- input -----------------------------------------------------------------------------

    private Decision handle(KeyStroke key) {
        if (this.editingId != null) {
            this.handleTextInput(key);
            return Decision.CONTINUE;
        }

        MenuNode current = this.path.peek();
        List<MenuNode> rows = current.children();

        if (key.getKeyType() == KeyType.Character) {
            char character = Character.toLowerCase(key.getCharacter());
            switch (character) {
                case ' ' -> this.row(rows).ifPresent(row -> {
                    if (row.kind() == MenuNode.Kind.TEXT) this.beginEditing(row);
                    else this.model.toggle(row.id());
                });
                case '?' -> this.showHelp = !this.showHelp;
                case 's' -> {
                    return Decision.START;
                }
                case 'q' -> {
                    if (this.path.size() > 1) {
                        this.ascend();
                        return Decision.CONTINUE;
                    }
                    this.cancelled = true;
                    this.versionFetcher.cancel();
                    return Decision.CANCEL;
                }
                default -> { }
            }
            return Decision.CONTINUE;
        }

        switch (key.getKeyType()) {
            case ArrowDown -> this.moveCursor(rows, 1);
            case ArrowUp -> this.moveCursor(rows, -1);
            case Enter -> this.row(rows).ifPresent(row -> {
                if (row.kind() == MenuNode.Kind.SUBMENU && row.isEnabled()) {
                    this.path.push(row);
                    this.cursor = 0;
                } else if (row.kind() == MenuNode.Kind.TEXT) {
                    this.beginEditing(row);
                } else {
                    this.model.toggle(row.id());
                }
            });
            case Escape, ArrowLeft -> {
                if (this.path.size() > 1) {
                    this.ascend();
                } else {
                    this.cancelled = true;
                    this.versionFetcher.cancel();
                    return Decision.CANCEL;
                }
            }
            case ArrowRight -> this.row(rows).ifPresent(row -> {
                if (row.kind() == MenuNode.Kind.SUBMENU && row.isEnabled()) {
                    this.path.push(row);
                    this.cursor = 0;
                }
            });
            // bulk selection is a keybind, never a row in the list
            case F10 -> this.model.selectAll(current.id());
            case F9 -> this.model.deselectAll(current.id());
            case EOF -> {
                this.cancelled = true;
                return Decision.CANCEL;
            }
            default -> { }
        }
        return Decision.CONTINUE;
    }

    private void ascend() {
        this.path.pop();
        this.cursor = 0;
    }

    private void beginEditing(MenuNode row) {
        this.editingId = row.id();
        this.editBuffer = row.value() == null ? "" : row.value();
    }

    private void handleTextInput(KeyStroke key) {
        switch (key.getKeyType()) {
            case Character -> this.editBuffer += key.getCharacter();
            case Backspace -> {
                if (!this.editBuffer.isEmpty()) {
                    this.editBuffer = this.editBuffer.substring(0, this.editBuffer.length() - 1);
                }
            }
            case Enter -> {
                this.model.setValue(this.editingId, this.editBuffer);
                this.editingId = null;
            }
            case Escape -> this.editingId = null;
            default -> { }
        }
    }

    private void moveCursor(List<MenuNode> rows, int delta) {
        if (rows.isEmpty()) return;
        int next = this.cursor;
        for (int i = 0; i < rows.size(); i++) {
            next = Math.floorMod(next + delta, rows.size());
            if (rows.get(next).kind() != MenuNode.Kind.LABEL) break;
        }
        this.cursor = next;
    }

    private Optional<MenuNode> row(List<MenuNode> rows) {
        if (this.cursor < 0 || this.cursor >= rows.size()) return Optional.empty();
        return Optional.of(rows.get(this.cursor));
    }

    public boolean wasCancelled() {
        return this.cancelled;
    }

    /** Package-visible for {@code NFMenuConfigScreenResponsivenessShould}. */
    int cursorForTesting() {
        return this.cursor;
    }

    // ---- drawing ---------------------------------------------------------------------------

    private void draw() throws IOException {
        TerminalSize size = this.screen.doResizeIfNecessary();
        if (size == null) size = this.screen.getTerminalSize();

        Painter painter = new Painter(this.screen.newTextGraphics(), size);
        painter.clear();

        int width = painter.width();
        int height = painter.height();

        MenuNode current = this.path.peek();
        List<MenuNode> rows = current.children();

        String title = this.path.size() == 1 ? "watchwolf build" : current.label();
        int helpHeight = this.showHelp ? 5 : 0;
        int listHeight = height - helpHeight - 2;

        painter.panel(0, 0, width, listHeight, title);
        this.drawStatusLine(painter, current, width, 1);
        this.drawRows(painter, rows, width, listHeight);

        // the bulk-select hint lives in the footer of the list it applies to
        painter.divider(0, listHeight - 2, width);
        painter.text(2, listHeight - 1, this.footerHint(current), Theme.DIM);

        if (this.showHelp) {
            this.drawHelp(painter, rows, 0, listHeight, width, helpHeight);
        }

        if (this.editingId != null) {
            painter.row(0, height - 1, width, " value: " + this.editBuffer + "_",
                    Theme.TEXT, Theme.SELECTED_BACKGROUND);
        } else {
            painter.text(1, height - 1,
                    "space toggle · ⏎ enter/edit · esc back · s start · ? help · q quit",
                    Theme.DIM);
        }

        this.screen.refresh();
    }

    /** Names the host being waited on -- never a blank pane while the network answers. */
    private void drawStatusLine(Painter painter, MenuNode current, int width, int row) {
        Async<?> pending = null;
        String what = null;

        if (MenuModel.ID_SPIGOT.equals(current.id())) {
            pending = this.model.spigotVersions();
            what = "Polling Spigot versions from hub.spigotmc.org";
        } else if (MenuModel.ID_PAPER.equals(current.id())) {
            pending = this.model.paperVersions();
            what = "Polling Paper versions from api.papermc.io";
        } else if (MenuModel.ID_USUAL_PLUGINS.equals(current.id())) {
            pending = this.model.usualPlugins();
            what = "Fetching the usual-plugins list from watchwolf.dev";
        }
        if (pending == null) return;

        if (pending.isLoading()) {
            String spinner = String.valueOf(
                    SPINNER.charAt((int) ((System.currentTimeMillis() / 100) % SPINNER.length())));
            painter.text(2, row, spinner + " " + pending.describe(what, Instant.now()), Theme.WARN);
        } else if (pending.hasFailed()) {
            painter.text(2, row, "! " + pending.failureDetail().orElse("failed"), Theme.BAD);
        }
    }

    private void drawRows(Painter painter, List<MenuNode> rows, int width, int listHeight) {
        int firstBodyRow = 2;
        int visible = listHeight - firstBodyRow - 2;
        int firstRow = Math.max(0, Math.min(this.cursor - visible / 2,
                Math.max(0, rows.size() - visible)));

        for (int i = 0; i < visible && (firstRow + i) < rows.size(); i++) {
            MenuNode row = rows.get(firstRow + i);
            int screenRow = firstBodyRow + i;
            boolean selected = (firstRow + i) == this.cursor;

            StringBuilder line = new StringBuilder("  ");
            line.append(row.marker()).append(' ');
            line.append(row.label());

            if (row.kind() == MenuNode.Kind.TEXT) {
                line.append(" ").append(".".repeat(Math.max(1, 24 - row.label().length())));
                line.append(" [ ").append(row.value()).append(" ]");
            } else if (row.kind() == MenuNode.Kind.SUBMENU) {
                line.append("  --->");
            }
            row.annotation().ifPresent(note -> line.append("   (").append(note).append(')'));
            row.disabledReason().ifPresent(reason -> line.append("   -- ").append(reason));

            if (selected) {
                painter.row(1, screenRow, width - 2, line.toString(),
                        Theme.TEXT, Theme.SELECTED_BACKGROUND);
            } else {
                painter.text(1, screenRow, line.toString(),
                        row.isEnabled() ? Theme.TEXT : Theme.DIM);
            }
        }
    }

    private String footerHint(MenuNode current) {
        boolean hasCheckboxes = current.children().stream()
                .anyMatch(child -> child.kind() == MenuNode.Kind.CHECK);
        String hint = "space toggle";
        if (hasCheckboxes) hint += " · F10 select all · F9 deselect all";
        if (this.path.size() > 1) hint += " · esc back";
        else hint += " · s start build";
        return hint;
    }

    private void drawHelp(Painter painter, List<MenuNode> rows, int x, int y, int width,
                          int height) {
        painter.panel(x, y, width, height, "help");

        MenuNode row = this.row(rows).orElse(null);
        if (row == null) return;

        String help = row.help().orElse(row.disabledReason()
                .map(reason -> "Unavailable: " + reason)
                .orElse("(no help for this entry)"));

        int line = y + 1;
        for (String wrapped : wrap(help, width - 4)) {
            if (line >= y + height - 1) break;
            painter.text(x + 2, line++, wrapped, Theme.DIM);
        }
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() + word.length() + 1 > width) {
                lines.add(line.toString());
                line = new StringBuilder();
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        this.versionFetcher.cancel();
    }
}
