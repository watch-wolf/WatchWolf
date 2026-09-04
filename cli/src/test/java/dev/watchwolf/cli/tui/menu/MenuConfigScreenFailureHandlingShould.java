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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What happens when hub.spigotmc.org (or fill.papermc.io) is down, driven through the real
 * {@link MenuConfigScreen} loop rather than {@link MenuModel} in isolation -- proving the failure
 * actually reaches the screen the way a real {@code BackgroundVersionFetcher} failure would, and
 * that the menu stays fully usable rather than freezing or crashing.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
public class MenuConfigScreenFailureHandlingShould {
    /** Spigot fails immediately, as a real fetch would report a connection refused; Paper and
     *  usual plugins never call back, so this test only exercises the Spigot failure. */
    private static final MenuConfigScreen.VersionFetcher SPIGOT_DOWN_FETCHER =
            new MenuConfigScreen.VersionFetcher() {
                @Override
                public void fetchSpigot(Consumer<Async<List<McVersion>>> onState) {
                    onState.accept(Async.failed("hub.spigotmc.org: Connection refused",
                            "Versions already on disk are still selectable; pass "
                                    + "--skip-spigot-build to skip Spigot entirely."));
                }

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
            this.terminal.addInput(new KeyStroke('q', false, false));
            this.terminal.addInput(new KeyStroke('q', false, false));
            this.terminal.addInput(new KeyStroke('q', false, false));
        }
        if (this.screenThread != null) {
            this.screenThread.join(2000);
            assertFalse(this.screenThread.isAlive(), "the screen loop did not quit in time");
        }
        if (this.screen != null) this.screen.close();
    }

    private MenuModel startScreenWithSpigotDown() throws InterruptedException, IOException {
        MenuModel model = new MenuModel(BuildPlan.defaults(), "/home/someone/WatchWolf");
        // the real ordering BuildCommand uses: installed versions are known before the screen
        // (and therefore the fetch) ever starts
        model.withInstalled(Set.of(McVersion.of("1.8.8")), Set.of());
        this.screen = new MenuConfigScreen(model, SPIGOT_DOWN_FETCHER);

        this.terminal = new DefaultVirtualTerminal(new TerminalSize(120, 40));
        Screen virtualScreen = new TerminalScreen(this.terminal);

        this.screenThread = new Thread(() -> {
            try {
                this.screen.runOn(virtualScreen);
            } catch (IOException ignored) {
                // the test ends the loop via 'q'
            }
        }, "menu-screen-failure-under-test");
        this.screenThread.setDaemon(true);
        this.screenThread.start();

        waitUntil(() -> this.screen.cursorForTesting() >= 0, 2000,
                "the screen never produced its first frame");
        return model;
    }

    @Test
    public void reachTheModelWithARealFetchFailure() throws Exception {
        MenuModel model = this.startScreenWithSpigotDown();

        waitUntil(() -> model.spigotVersions().hasFailed(), 2000,
                "the failure never reached the model through the screen's real wiring");
        assertTrue(model.spigotVersions().failureDetail().orElseThrow()
                .contains("hub.spigotmc.org"));
    }

    @Test
    public void staySelectableAndNavigableAfterEnteringServerJarsWithSpigotDown() throws Exception {
        MenuModel model = this.startScreenWithSpigotDown();
        waitUntil(() -> model.spigotVersions().hasFailed(), 2000, "fetch never failed");

        int serverJarsIndex = indexOf(model, MenuModel.ID_SERVER_JARS);
        for (int i = 0; i < serverJarsIndex; i++) {
            this.terminal.addInput(new KeyStroke(KeyType.ArrowDown));
        }
        waitUntil(() -> this.screen.cursorForTesting() == serverJarsIndex, 2000,
                "never reached 'Server jars'");

        this.terminal.addInput(new KeyStroke(KeyType.Enter));   // descend into "Server jars"
        waitUntil(() -> this.screen.cursorForTesting() == 0, 2000, "never descended");

        this.terminal.addInput(new KeyStroke(KeyType.Enter));   // "Spigot" is the first row there
        waitUntil(() -> this.screen.cursorForTesting() == 0, 2000, "never descended into Spigot");

        // the version already on disk must still be there and selectable, per the failure's own
        // remedy text -- this is what proves the menu did not just go blank
        MenuNode spigot = model.node(MenuModel.ID_SPIGOT).orElseThrow();
        assertEquals(1, spigot.children().size());
        assertEquals("1.8.8", spigot.children().get(0).label());

        this.terminal.addInput(new KeyStroke(' ', false, false));   // toggle it
        waitUntil(() -> spigot.children().get(0).isChecked(), 2000,
                "space did not select the locally-installed version");

        // and the menu overall is still fully usable: back out cleanly and start the build
        this.terminal.addInput(new KeyStroke(KeyType.Escape));
        waitUntil(() -> this.screen.cursorForTesting() == 0, 2000, "never ascended out of Spigot");
        this.terminal.addInput(new KeyStroke(KeyType.Escape));
        waitUntil(() -> this.screen.cursorForTesting() == serverJarsIndex, 2000,
                "never ascended back to the root");

        this.terminal.addInput(new KeyStroke('s', false, false));
        this.screenThread.join(2000);
        assertFalse(this.screenThread.isAlive(), "'s' must still start the build with a failed "
                + "fetch in the tree, not hang or crash");
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
