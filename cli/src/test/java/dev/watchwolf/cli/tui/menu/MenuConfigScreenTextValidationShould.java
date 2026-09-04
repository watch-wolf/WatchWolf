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
 * The bug report: "Parallel Spigot builders" accepted an empty box or letters, which
 * {@code MenuModel.toBuildPlan()} then silently turned into 1 builder with no word to the user
 * that what they typed was ignored. Drives the real {@link MenuConfigScreen} loop -- the rejection
 * lives in its {@code handleTextInput}, not in {@link MenuModel}, so a model-only test would not
 * catch a regression here.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
public class MenuConfigScreenTextValidationShould {
    private static final MenuConfigScreen.VersionFetcher NO_OP_FETCHER =
            new MenuConfigScreen.VersionFetcher() {
                @Override public void fetchSpigot(Consumer<Async<List<McVersion>>> onState) { }
                @Override public void fetchPaper(Consumer<Async<List<McVersion>>> onState) { }
                @Override public void fetchUsualPlugins(
                        Consumer<Async<List<WatchWolfWebClient.UsualPlugin>>> onState) { }
                @Override public void cancel() { }
            };

    private MenuModel model;
    private MenuConfigScreen screen;
    private Thread screenThread;
    private DefaultVirtualTerminal terminal;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (this.terminal != null) {
            this.terminal.addInput(new KeyStroke(KeyType.Escape));   // cancel any open edit first
            this.terminal.addInput(new KeyStroke('q', false, false));
        }
        if (this.screenThread != null) {
            this.screenThread.join(2000);
            assertFalse(this.screenThread.isAlive(), "the screen loop did not quit in time");
        }
        if (this.screen != null) this.screen.close();
    }

    private void startScreenEditingThreads() throws InterruptedException, IOException {
        this.model = new MenuModel(BuildPlan.defaults(), "/home/someone/WatchWolf");
        this.screen = new MenuConfigScreen(this.model, NO_OP_FETCHER);

        this.terminal = new DefaultVirtualTerminal(new TerminalSize(120, 40));
        Screen virtualScreen = new TerminalScreen(this.terminal);

        this.screenThread = new Thread(() -> {
            try {
                this.screen.runOn(virtualScreen);
            } catch (IOException ignored) {
                // the test ends the loop via 'q'
            }
        }, "menu-screen-text-validation-under-test");
        this.screenThread.setDaemon(true);
        this.screenThread.start();

        waitUntil(() -> this.screen.cursorForTesting() >= 0, 2000,
                "the screen never produced its first frame");

        int threadsIndex = indexOf(this.model, MenuModel.ID_THREADS);
        for (int i = 0; i < threadsIndex; i++) {
            this.terminal.addInput(new KeyStroke(KeyType.ArrowDown));
        }
        waitUntil(() -> this.screen.cursorForTesting() == threadsIndex, 2000,
                "never reached 'Parallel Spigot builders'");
        this.terminal.addInput(new KeyStroke(KeyType.Enter));   // begin editing
    }

    /** Clears whatever the field started with, by backspacing more than it could possibly hold. */
    private void clearField() {
        for (int i = 0; i < 10; i++) {
            this.terminal.addInput(new KeyStroke(KeyType.Backspace));
        }
    }

    private void type(String text) {
        for (char c : text.toCharArray()) {
            this.terminal.addInput(new KeyStroke(c, false, false));
        }
    }

    @Test
    public void refuseToCommitAnEmptyValue() throws Exception {
        this.startScreenEditingThreads();
        this.clearField();

        this.terminal.addInput(new KeyStroke(KeyType.Enter));
        Thread.sleep(150);   // let the loop process it; there is nothing to waitUntil for a no-op

        assertEquals("1", this.model.node(MenuModel.ID_THREADS).orElseThrow().value(),
                "an empty value must never overwrite the field");
        assertEquals(1, this.model.toBuildPlan().parallelBuilders());
        this.assertStillEditing();
    }

    @Test
    public void refuseToCommitNonNumericCharacters() throws Exception {
        this.startScreenEditingThreads();
        this.clearField();
        this.type("abc");

        this.terminal.addInput(new KeyStroke(KeyType.Enter));
        Thread.sleep(150);

        assertEquals("1", this.model.node(MenuModel.ID_THREADS).orElseThrow().value(),
                "non-numeric input must never overwrite the field");
        this.assertStillEditing();
    }

    /** Arrow keys move the cursor when NOT editing, and do nothing while editing (see
     *  MenuConfigScreen.handleTextInput's default case) -- an unmoved cursor after ArrowDown is
     *  this test's only externally-observable proof the rejection kept edit mode open. */
    private void assertStillEditing() throws InterruptedException {
        int before = this.screen.cursorForTesting();
        this.terminal.addInput(new KeyStroke(KeyType.ArrowDown));
        Thread.sleep(150);
        assertEquals(before, this.screen.cursorForTesting(),
                "a rejected edit must stay open, not silently drop back to navigation");
    }

    @Test
    public void acceptAValidValueAndLeaveEditMode() throws Exception {
        this.startScreenEditingThreads();
        this.clearField();
        this.type("4");

        this.terminal.addInput(new KeyStroke(KeyType.Enter));
        waitUntil(() -> "4".equals(this.model.node(MenuModel.ID_THREADS).orElseThrow().value()),
                2000, "a valid value must be committed");
        assertEquals(4, this.model.toBuildPlan().parallelBuilders());

        // and it actually left edit mode: arrow keys move the cursor again, they don't type digits
        int before = this.screen.cursorForTesting();
        this.terminal.addInput(new KeyStroke(KeyType.ArrowDown));
        waitUntil(() -> this.screen.cursorForTesting() != before, 2000,
                "Enter must have exited edit mode for a valid value");
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
