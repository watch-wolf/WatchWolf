package dev.watchwolf.cli.tui.menu;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;

import dev.watchwolf.cli.model.BuildPlan;
import dev.watchwolf.cli.model.McVersion;
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
 * Same non-functional requirement as {@code NFMonitorScreenResponsivenessShould} (under 120ms from
 * keypress to the model reflecting it), applied to the install menu -- a second, independently
 * written loop that had the identical bug. See {@link MenuConfigScreen#runOn} for the fix.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
public class NFMenuConfigScreenResponsivenessShould {
    private static final long LATENCY_BUDGET_MILLIS = 120;

    /** Never calls back -- this class is not exercising the version-fetch behaviour. */
    private static final MenuConfigScreen.VersionFetcher NO_OP_FETCHER =
            new MenuConfigScreen.VersionFetcher() {
                @Override public void fetchSpigot(Consumer<Async<List<McVersion>>> onState) { }
                @Override public void fetchPaper(Consumer<Async<List<McVersion>>> onState) { }
                @Override public void cancel() { }
            };

    private MenuConfigScreen screen;
    private Thread screenThread;
    private DefaultVirtualTerminal terminal;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (this.terminal != null) {
            // 'q' at the root pushes CANCEL, ending the loop
            this.terminal.addInput(new KeyStroke('q', false, false));
        }
        if (this.screenThread != null) {
            this.screenThread.join(2000);
            assertFalse(this.screenThread.isAlive(), "the screen loop did not quit in time");
        }
        if (this.screen != null) this.screen.close();
    }

    private void startScreen() throws InterruptedException, IOException {
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
        }, "menu-screen-under-test");
        this.screenThread.setDaemon(true);
        this.screenThread.start();

        // the root menu has several rows (install path, branch, threads, the clone checkboxes,
        // ...) by construction, so this only waits for the loop to reach its first frame
        waitUntil(() -> this.screen.cursorForTesting() >= 0, 2000,
                "the screen never produced its first frame");
    }

    @Test
    public void reactToASingleKeypressWithinTheLatencyBudget() throws Exception {
        this.startScreen();
        int before = this.screen.cursorForTesting();

        long startNanos = System.nanoTime();
        this.terminal.addInput(new KeyStroke(KeyType.ArrowDown));

        waitUntil(() -> this.screen.cursorForTesting() != before, LATENCY_BUDGET_MILLIS * 3,
                "the cursor never moved");
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertTrue(elapsedMillis < LATENCY_BUDGET_MILLIS,
                "took " + elapsedMillis + "ms to react to one keypress; budget is "
                        + LATENCY_BUDGET_MILLIS + "ms");
    }

    /**
     * The bug this exists for: the original loop drained one key per ~80ms sleep, so a burst of
     * taps -- normal usage -- compounded toward (key count x sleep) before the selection visibly
     * finished moving. A single-key test alone would not catch this.
     */
    @Test
    public void fullyDrainABurstOfKeypressesWithinTheLatencyBudget() throws Exception {
        this.startScreen();
        int before = this.screen.cursorForTesting();

        long startNanos = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            this.terminal.addInput(new KeyStroke(KeyType.ArrowDown));
        }

        int target = before + 5;   // the root menu's first several rows are all selectable
        waitUntil(() -> this.screen.cursorForTesting() == target, LATENCY_BUDGET_MILLIS * 3,
                "the burst never fully caught up (cursor stuck at "
                        + this.screen.cursorForTesting() + ", wanted " + target + ")");
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertTrue(elapsedMillis < LATENCY_BUDGET_MILLIS,
                "took " + elapsedMillis + "ms to fully process a 5-key burst; budget is "
                        + LATENCY_BUDGET_MILLIS + "ms (all 5 must land in the very next frame)");
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
