package dev.watchwolf.cli.tui.monitor;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;

import dev.watchwolf.cli.bundle.BundleWriter;
import dev.watchwolf.cli.fake.FakeDockerFacade;
import dev.watchwolf.cli.inventory.EnvironmentScanner;
import dev.watchwolf.cli.inventory.SocketAndLogClientDiscovery;
import dev.watchwolf.cli.io.NioFileGateway;
import dev.watchwolf.cli.layout.InstallLayout;
import dev.watchwolf.cli.layout.RuntimeFlavor;
import dev.watchwolf.cli.net.HostInterfaces;
import dev.watchwolf.cli.net.PortProbe;
import dev.watchwolf.cli.progress.ProgressSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A non-functional test: the dashboard must react to a keypress in well under 120ms, the
 * threshold below which a terminal UI stops feeling immediate. This is what actually caught (and
 * now guards against regressing) a real bug in the original loop -- see
 * {@link MonitorScreen#runOn} for the fix and its reasoning.
 *
 * <p>Drives the real {@link MonitorScreen} loop over a
 * {@link DefaultVirtualTerminal}, which accepts {@link KeyStroke}s programmatically with no pty
 * involved -- so this runs as an ordinary hermetic unit test (no Docker daemon, no real terminal),
 * fast enough to run on every build.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
public class NFMonitorScreenResponsivenessShould {
    /** The requirement: under 120ms from keypress to the model reflecting it. */
    private static final long LATENCY_BUDGET_MILLIS = 120;

    @TempDir
    Path base;

    private MonitorScreen screen;
    private Thread screenThread;
    private DefaultVirtualTerminal terminal;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (this.terminal != null) {
            // 'q' at the top level quits the loop cleanly
            this.terminal.addInput(new KeyStroke('q', false, false));
        }
        if (this.screenThread != null) {
            this.screenThread.join(2000);
            assertFalse(this.screenThread.isAlive(), "the screen loop did not quit in time");
        }
        if (this.screen != null) this.screen.close();
    }

    /** Three MC_Server rows, so there is real distance for the cursor to travel. */
    private MonitorScreen startScreenWithThreeServers() throws IOException, InterruptedException {
        FakeDockerFacade docker = new FakeDockerFacade()
                .withContainer("ServersManager").running().publishing(8000, 8000).done()
                .withContainer("ClientsManager").running().done()
                .withContainer("MC_Server-1000000000001").running()
                        .publishing(8001, 25565).publishing(8002, 25566).done()
                .withContainer("MC_Server-1000000000002").running()
                        .publishing(8003, 25565).publishing(8004, 25566).done()
                .withContainer("MC_Server-1000000000003").running()
                        .publishing(8005, 25565).publishing(8006, 25566).done();

        InstallLayout layout = new InstallLayout(this.base, RuntimeFlavor.RELEASE);
        EnvironmentScanner scanner = new EnvironmentScanner(docker, new NioFileGateway(), layout,
                new SocketAndLogClientDiscovery(docker), new PortProbe(), new HostInterfaces(),
                Clock.systemUTC());
        MonitorPoller poller = new MonitorPoller(scanner, Duration.ofSeconds(30));
        BundleWriter bundleWriter = new BundleWriter(docker, new NioFileGateway(), layout,
                new HostInterfaces(), Clock.systemUTC());

        this.screen = new MonitorScreen(layout, docker, new NioFileGateway(), poller,
                bundleWriter, ProgressSink.discarding(), Duration.ofSeconds(30));

        this.terminal = new DefaultVirtualTerminal(new TerminalSize(120, 40));
        Screen virtualScreen = new TerminalScreen(this.terminal);

        this.screenThread = new Thread(() -> {
            try {
                this.screen.runOn(virtualScreen);
            } catch (IOException ignored) {
                // the test ends the loop via 'q'; any IOException here would fail the assertions
                // below instead, since the model would simply stop updating
            }
        }, "monitor-screen-under-test");
        this.screenThread.setDaemon(true);
        this.screenThread.start();

        // let the loop reach its polling state; not part of what we measure below
        waitUntil(() -> this.screen.modelForTesting() != null
                        && !this.screen.modelForTesting().rows().isEmpty(),
                2000, "the screen never produced its first frame");

        return this.screen;
    }

    @Test
    public void reactToASingleKeypressWithinTheLatencyBudget() throws Exception {
        this.startScreenWithThreeServers();
        this.screen.modelForTesting().moveDown();   // off row 0 (a manager) so Down has somewhere to go
        int before = this.screen.modelForTesting().cursor();

        long startNanos = System.nanoTime();
        this.terminal.addInput(new KeyStroke(KeyType.ArrowDown));

        waitUntilCursorMoves(before);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertTrue(elapsedMillis < LATENCY_BUDGET_MILLIS,
                "took " + elapsedMillis + "ms to react to one keypress; budget is "
                        + LATENCY_BUDGET_MILLIS + "ms");
    }

    /**
     * The bug this whole test class exists for: the original loop called {@code pollInput()} once
     * per ~200ms sleep, so a burst of keys -- exactly what happens when someone taps an arrow key
     * a few times to move the selection, completely normal usage -- drained at one key per sleep,
     * compounding toward roughly (key count x sleep) before the view caught up. A single-key test
     * alone would not have caught this; this one specifically would have.
     */
    @Test
    public void fullyDrainABurstOfKeypressesWithinTheLatencyBudget() throws Exception {
        this.startScreenWithThreeServers();
        int before = this.screen.modelForTesting().cursor();

        long startNanos = System.nanoTime();
        // five taps, queued together the way a human bursts them -- the old loop took ~200ms EACH
        for (int i = 0; i < 5; i++) {
            this.terminal.addInput(new KeyStroke(KeyType.ArrowDown));
        }

        int expectedAfter = before;
        for (int i = 0; i < 5; i++) {
            expectedAfter = this.advanceExpectedCursor(expectedAfter);
        }
        int target = expectedAfter;

        waitUntil(() -> this.screen.modelForTesting().cursor() == target,
                LATENCY_BUDGET_MILLIS * 3, "the burst never fully caught up");
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertTrue(elapsedMillis < LATENCY_BUDGET_MILLIS,
                "took " + elapsedMillis + "ms to fully process a 5-key burst; budget is "
                        + LATENCY_BUDGET_MILLIS + "ms (all 5 must land in the very next frame)");
    }

    /** Mirrors MonitorModel.moveDown()'s row-skipping without depending on its internals. */
    private int advanceExpectedCursor(int from) {
        for (int i = from + 1; i < this.screen.modelForTesting().rows().size(); i++) {
            if (this.screen.modelForTesting().rows().get(i).selectable()) return i;
        }
        return from;
    }

    private void waitUntilCursorMoves(int before) throws InterruptedException {
        waitUntil(() -> this.screen.modelForTesting().cursor() != before,
                LATENCY_BUDGET_MILLIS * 3, "the cursor never moved");
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
