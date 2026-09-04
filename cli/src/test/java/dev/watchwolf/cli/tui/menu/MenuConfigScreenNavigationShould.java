package dev.watchwolf.cli.tui.menu;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;

import dev.watchwolf.cli.model.BuildPlan;
import dev.watchwolf.cli.model.McVersion;
import dev.watchwolf.cli.remote.WatchWolfWebClient;
import dev.watchwolf.cli.tui.Async;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bug report: leaving a submenu with Escape/{@code q} always landed back on row 0 of the
 * parent list, no matter where the cursor had been before descending -- annoying when the parent
 * list is long and you are bouncing in and out of a submenu to compare options.
 *
 * <p>Drives the real {@link MenuConfigScreen} loop over a {@link DefaultVirtualTerminal}, the same
 * pattern as {@code NFMenuConfigScreenResponsivenessShould}, so this exercises the actual key
 * handling rather than {@link MenuConfigScreen#descend}/{@code ascend} in isolation.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
public class MenuConfigScreenNavigationShould {
    /** Never calls back -- this class is not exercising the version-fetch behaviour. */
    private static final MenuConfigScreen.VersionFetcher NO_OP_FETCHER =
            new MenuConfigScreen.VersionFetcher() {
                @Override public void fetchSpigot(Consumer<Async<List<McVersion>>> onState) { }
                @Override public void fetchPaper(Consumer<Async<List<McVersion>>> onState) { }
                @Override public void fetchUsualPlugins(
                        Consumer<Async<List<WatchWolfWebClient.UsualPlugin>>> onState) { }
                @Override public void cancel() { }
            };

    private MenuConfigScreen screen;
    private Thread screenThread;
    private DefaultVirtualTerminal terminal;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (this.terminal != null) {
            // 'q' backs out of a submenu first (as it should) and only cancels at the root, so
            // these tests -- which may end mid-submenu -- send it twice; harmless if the first
            // already cancelled
            this.terminal.addInput(new KeyStroke('q', false, false));
            this.terminal.addInput(new KeyStroke('q', false, false));
        }
        if (this.screenThread != null) {
            this.screenThread.join(2000);
            assertFalse(this.screenThread.isAlive(), "the screen loop did not quit in time");
        }
        if (this.screen != null) this.screen.close();
    }

    private MenuModel startScreen() throws InterruptedException, IOException {
        MenuModel model = new MenuModel(BuildPlan.defaults(), "/home/someone/WatchWolf");
        this.screen = new MenuConfigScreen(model, NO_OP_FETCHER);

        this.terminal = new DefaultVirtualTerminal(new TerminalSize(120, 40));
        Screen virtualScreen = new TerminalScreen(this.terminal);

        this.screenThread = new Thread(() -> {
            try {
                this.screen.runOn(virtualScreen);
            } catch (IOException ignored) {
                // the test ends the loop via 'q'
            }
        }, "menu-screen-navigation-under-test");
        this.screenThread.setDaemon(true);
        this.screenThread.start();

        waitUntil(() -> this.screen.cursorForTesting() >= 0, 2000,
                "the screen never produced its first frame");
        return model;
    }

    @Test
    public void restoreTheParentsCursorPositionOnEscape() throws Exception {
        MenuModel model = this.startScreen();
        int serverJarsIndex = indexOf(model, MenuModel.ID_SERVER_JARS);
        assertTrue(serverJarsIndex > 0, "the fixture assumes 'Server jars' is not row 0");

        this.moveCursorTo(serverJarsIndex);
        assertEquals(serverJarsIndex, this.screen.cursorForTesting());

        this.terminal.addInput(new KeyStroke(KeyType.Enter));   // descend into "Server jars"
        waitUntil(() -> this.screen.cursorForTesting() == 0, 2000,
                "descending must start the submenu at row 0");

        this.terminal.addInput(new KeyStroke(KeyType.ArrowDown));   // move off row 0 inside it
        waitUntil(() -> this.screen.cursorForTesting() == 1, 2000, "never moved inside the submenu");

        this.terminal.addInput(new KeyStroke(KeyType.Escape));
        waitUntil(() -> this.screen.cursorForTesting() == serverJarsIndex, 2000,
                "ascending must restore the parent's cursor, not reset to row 0");
    }

    @Test
    public void restoreThroughTwoNestedLevels() throws Exception {
        MenuModel model = this.startScreen();
        int usualPluginsIndex = indexOf(model, MenuModel.ID_USUAL_PLUGINS);
        assertTrue(usualPluginsIndex > 0, "the fixture assumes 'Usual plugins' is not row 0");

        this.moveCursorTo(usualPluginsIndex);
        this.terminal.addInput(new KeyStroke(KeyType.Enter));   // descend into "Usual plugins"
        waitUntil(() -> this.screen.cursorForTesting() == 0, 2000, "never descended");

        // "Usual plugins" starts empty until the (no-op, in this test) fetch completes, so there
        // is nothing to move the cursor onto in there -- ascend immediately and go straight back
        // down to prove the SAME level restores correctly a second time
        this.terminal.addInput(new KeyStroke('q', false, false));
        waitUntil(() -> this.screen.cursorForTesting() == usualPluginsIndex, 2000,
                "first ascend must restore the root's cursor");

        this.terminal.addInput(new KeyStroke(KeyType.Enter));
        waitUntil(() -> this.screen.cursorForTesting() == 0, 2000, "never re-descended");
        this.terminal.addInput(new KeyStroke('q', false, false));
        waitUntil(() -> this.screen.cursorForTesting() == usualPluginsIndex, 2000,
                "the saved position must survive a second round trip, not just the first");
    }

    private void moveCursorTo(int target) throws InterruptedException {
        for (int i = 0; i < target; i++) {
            this.terminal.addInput(new KeyStroke(KeyType.ArrowDown));
        }
        waitUntil(() -> this.screen.cursorForTesting() == target, 2000,
                "never reached row " + target);
    }

    private static int indexOf(MenuModel model, String id) {
        List<MenuNode> rows = model.root().children();
        for (int i = 0; i < rows.size(); i++) {
            if (id.equals(rows.get(i).id())) return i;
        }
        throw new IllegalStateException("no row with id " + id);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMillis,
                                  String failureMessage) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(1);
        }
        fail(failureMessage);
    }
}
