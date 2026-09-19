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
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code s} always started the build, but nothing on the screen said so -- the footer hint is easy
 * to miss and the menu otherwise looks like a form with no submit. There is now a row for it at the
 * bottom, and this pins down that reaching it and pressing Enter (or space) is enough.
 *
 * <p>It is the one row that is not a setting, so it must never be reachable by the keys that change
 * settings in bulk either: F8/F9 tick and untick checkboxes, and an action row has nothing to tick.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
public class MenuConfigScreenStartRowShould {
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
    private final AtomicReference<Optional<BuildPlan>> result = new AtomicReference<>();

    @AfterEach
    void tearDown() throws InterruptedException {
        if (this.terminal != null && this.result.get() == null) {
            this.terminal.addInput(new KeyStroke('q', false, false));
            this.terminal.addInput(new KeyStroke('q', false, false));
        }
        if (this.screenThread != null) {
            this.screenThread.join(2000);
            assertFalse(this.screenThread.isAlive(), "the screen loop did not quit in time");
        }
        if (this.screen != null) this.screen.close();
    }

    @Test
    public void offerTheRowAtTheVeryBottom() throws Exception {
        MenuModel model = this.startScreen();
        List<MenuNode> rows = model.root().children();

        MenuNode last = rows.get(rows.size() - 1);
        assertEquals(MenuModel.ID_START_BUILD, last.id(), "the button belongs after the options");
        assertEquals(MenuNode.Kind.ACTION, last.kind());
    }

    @Test
    public void startTheBuildWhenTheRowIsEntered() throws Exception {
        MenuModel model = this.startScreen();
        this.moveToTheStartRow(model);

        this.terminal.addInput(new KeyStroke(KeyType.Enter));

        this.waitForAResult();
        assertTrue(this.result.get().isPresent(), "Enter on the row must start the build");
    }

    @Test
    public void startTheBuildOnSpaceToo() throws Exception {
        // space is "toggle" everywhere else, so on a row with nothing to toggle it has to mean the
        // obvious thing rather than quietly doing nothing
        MenuModel model = this.startScreen();
        this.moveToTheStartRow(model);

        this.terminal.addInput(new KeyStroke(' ', false, false));

        this.waitForAResult();
        assertTrue(this.result.get().isPresent());
    }

    @Test
    public void neverBeTickedByTheSelectAllKey() throws Exception {
        MenuModel model = this.startScreen();

        model.selectAll(model.root().id());

        MenuNode last = model.root().children().get(model.root().children().size() - 1);
        assertEquals(MenuModel.ID_START_BUILD, last.id());
        assertFalse(last.isChecked(),
                "an action row holds no value, so 'select all' cannot tick it");
        assertEquals("   ", last.marker(), "and it must not draw a checkbox either");
    }

    private void moveToTheStartRow(MenuModel model) throws InterruptedException {
        int target = model.root().children().size() - 1;
        for (int i = 0; i < target; i++) {
            this.terminal.addInput(new KeyStroke(KeyType.ArrowDown));
        }
        waitUntil(() -> this.screen.cursorForTesting() == target, 2000,
                "never reached the 'Start build' row");
    }

    private MenuModel startScreen() throws InterruptedException, IOException {
        MenuModel model = new MenuModel(BuildPlan.defaults(), "/home/someone/WatchWolf");
        this.screen = new MenuConfigScreen(model, NO_OP_FETCHER);

        this.terminal = new DefaultVirtualTerminal(new TerminalSize(120, 40));
        Screen virtualScreen = new TerminalScreen(this.terminal);

        this.screenThread = new Thread(() -> {
            try {
                this.result.set(this.screen.runOn(virtualScreen));
            } catch (IOException ignored) {
                // the test always ends the loop through a key
            }
        }, "menu-screen-start-row-under-test");
        this.screenThread.setDaemon(true);
        this.screenThread.start();

        waitUntil(() -> this.screen.cursorForTesting() >= 0, 2000,
                "the screen never produced its first frame");
        return model;
    }

    private void waitForAResult() throws InterruptedException {
        waitUntil(() -> this.result.get() != null, 2000, "the menu never returned");
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
