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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bug report: a Minecraft server's log is a file with no push notification, so
 * {@link MonitorScreen} re-reads its tail on a timer (see {@code syncLogStream}). Scrolling back to
 * read history, then having that periodic re-read silently snap the view back to the live edge a
 * second later, makes the log pane unusable for reading anything.
 *
 * <p>Unlike {@code LogRingShould} (which tests {@link LogRing}'s own contract in isolation, and
 * would still pass even if {@link MonitorScreen} used {@code clear()+addAll()} instead of
 * {@code replaceAll()}), this drives the real screen loop end-to-end over a
 * {@link DefaultVirtualTerminal} -- entering the entity, scrolling back, and asserting the periodic
 * reload that follows does not move the view. That gap is exactly what this class exists to close.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
public class MonitorScreenLogViewingShould {
    private static final String SESSION_ID = "1000000000001";

    @TempDir
    Path base;

    private MonitorScreen screen;
    private Thread screenThread;
    private DefaultVirtualTerminal terminal;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (this.terminal != null) {
            // 'q' backs out of the entity view first (as it should -- it doubles as "back" while
            // drilled in) and only quits at the top level, so these tests -- which end mid-entity --
            // need it twice; a harmless no-op if the first already quit
            this.terminal.addInput(new KeyStroke('q', false, false));
            this.terminal.addInput(new KeyStroke('q', false, false));
        }
        if (this.screenThread != null) {
            this.screenThread.join(2000);
            assertFalse(this.screenThread.isAlive(), "the screen loop did not quit in time");
        }
        if (this.screen != null) this.screen.close();
    }

    private void startScreenWithOneRunningServer() throws IOException, InterruptedException {
        Path logFile = this.base.resolve("ServersManager").resolve("ci")
                .resolve(RuntimeFlavor.RELEASE.directoryName()).resolve("logs").resolve(SESSION_ID)
                .resolve("latest.log");
        Files.createDirectories(logFile.getParent());
        Files.writeString(logFile, "[00:00:00] line 0\n[00:00:01] line 1\n[00:00:02] line 2\n"
                + "[00:00:03] line 3\n[00:00:04] line 4\n[00:00:05] line 5\n");

        FakeDockerFacade docker = new FakeDockerFacade()
                .withContainer("ServersManager").running().publishing(8000, 8000).done()
                .withContainer("ClientsManager").running().done()
                .withContainer("MC_Server-" + SESSION_ID).running()
                        .publishing(8001, 25565).publishing(8002, 25566).done();

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
                // the test ends the loop via 'q'; an IOException here would fail the assertions
                // below instead, since the model would simply stop updating
            }
        }, "monitor-screen-under-test");
        this.screenThread.setDaemon(true);
        this.screenThread.start();

        waitUntil(() -> this.screen.modelForTesting() != null
                        && !this.screen.modelForTesting().rows().isEmpty(),
                2000, "the screen never produced its first frame");

        // through the keyboard, not a direct model call: a direct moveDown() races the screen
        // thread, and Enter can fire before it lands, entering row 0 (ServersManager) instead
        int before = this.screen.modelForTesting().cursor();
        this.terminal.addInput(new KeyStroke(KeyType.ArrowDown));
        waitUntil(() -> this.screen.modelForTesting().cursor() != before,
                2000, "ArrowDown never moved off row 0 onto the server row");

        this.terminal.addInput(new KeyStroke(KeyType.Enter));
        waitUntil(() -> this.screen.modelForTesting().isInEntityView(),
                2000, "never entered the server's entity view");
        assertInstanceOf(EntityView.LogSource.FileLog.class,
                this.screen.modelForTesting().entityView().orElseThrow().logSource(),
                "the cursor must have landed on the server row, not a manager");
        waitUntil(() -> !this.screen.logsForTesting().window(10).isEmpty(),
                2000, "the log was never populated from the file");
    }

    @Test
    public void keepScrollPositionAcrossThePeriodicFileLogReload() throws Exception {
        this.startScreenWithOneRunningServer();

        // scroll back off the live edge, as someone reading history would
        this.terminal.addInput(new KeyStroke(KeyType.ArrowUp));
        this.terminal.addInput(new KeyStroke(KeyType.ArrowUp));
        waitUntil(() -> !this.screen.logsForTesting().isFollowing(),
                2000, "ArrowUp never scrolled the log back");

        // the file's content is unchanged, so any reload the screen performs on its own timer
        // must not touch scrollBack -- give that timer ample time to fire at least once
        Thread.sleep(1500);

        assertFalse(this.screen.logsForTesting().isFollowing(),
                "a periodic re-read of the log file must not silently jump back to the live edge "
                        + "while the user is scrolled back reading history");
    }

    @Test
    public void hideFollowingForAFinishedServersLog() throws Exception {
        // a stopped container will never append to its log file again, so "following" -- which
        // implies more output is still coming -- must not be offered
        Path logFile = this.base.resolve("ServersManager").resolve("ci")
                .resolve(RuntimeFlavor.RELEASE.directoryName()).resolve("logs").resolve(SESSION_ID)
                .resolve("latest.log");
        Files.createDirectories(logFile.getParent());
        Files.writeString(logFile, "[00:00:00] line 0\n[00:00:01] line 1\n");

        FakeDockerFacade docker = new FakeDockerFacade()
                .withContainer("ServersManager").running().publishing(8000, 8000).done()
                .withContainer("ClientsManager").running().done();
        // no MC_Server-* container: this is the "finished run, only logs/<id>/ survives" case

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
                // the test ends the loop via 'q'
            }
        }, "monitor-screen-under-test-finished");
        this.screenThread.setDaemon(true);
        this.screenThread.start();

        waitUntil(() -> this.screen.modelForTesting() != null
                        && !this.screen.modelForTesting().rows().isEmpty(),
                2000, "the screen never produced its first frame");

        int before = this.screen.modelForTesting().cursor();
        this.terminal.addInput(new KeyStroke(KeyType.ArrowDown));
        waitUntil(() -> this.screen.modelForTesting().cursor() != before,
                2000, "ArrowDown never moved off row 0 onto the server row");

        this.terminal.addInput(new KeyStroke(KeyType.Enter));
        waitUntil(() -> this.screen.modelForTesting().isInEntityView(),
                2000, "never entered the finished server's entity view");
        assertInstanceOf(EntityView.LogSource.FileLog.class,
                this.screen.modelForTesting().entityView().orElseThrow().logSource(),
                "the cursor must have landed on the server row, not a manager");

        assertFalse(this.screen.modelForTesting().entityView().orElseThrow().logIsLive(),
                "a finished server's log will never grow again, so it must not be marked live");

        // scroll back, then try to jump to the tail via 'f' -- it must be a no-op, not a fake
        // "following" state for a source that can never produce another line
        this.terminal.addInput(new KeyStroke(KeyType.ArrowUp));
        waitUntil(() -> !this.screen.logsForTesting().isFollowing(),
                2000, "ArrowUp never scrolled the log back");
        this.terminal.addInput(new KeyStroke('f', false, false));
        Thread.sleep(200);

        assertFalse(this.screen.logsForTesting().isFollowing(),
                "'f' must do nothing on a finished server's log -- there is nothing left to follow");
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
