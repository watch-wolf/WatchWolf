package dev.watchwolf.cli.tui.install;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;

import dev.watchwolf.cli.step.StepOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the real screen loop over a {@link DefaultVirtualTerminal}, the same way the menu screens
 * are tested -- so this covers the actual key handling, not a re-implementation of it.
 *
 * <p>The one that matters is the double confirmation: an install is an hour of somebody's evening,
 * and a single stray {@code q} must not throw it away.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
public class InstallProgressScreenShould {
    private DefaultVirtualTerminal terminal;
    private Thread screenThread;
    private final AtomicReference<InstallProgressModel.Ending> ending = new AtomicReference<>();

    @AfterEach
    void tearDown() throws InterruptedException {
        // a test that deliberately leaves the install running still has to close the screen, and
        // closing it takes two keys by design -- that is the behaviour under test
        if (this.terminal != null && this.ending.get() == null) {
            this.terminal.addInput(new KeyStroke('q', false, false));
            this.terminal.addInput(new KeyStroke('y', false, false));
        }
        if (this.screenThread != null) {
            this.screenThread.join(3000);
            assertFalse(this.screenThread.isAlive(), "the screen loop did not quit in time");
        }
    }

    @Test
    public void needASecondKeyToAbort() throws Exception {
        InstallProgressModel model = this.startScreen();

        this.terminal.addInput(new KeyStroke('q', false, false));
        Thread.sleep(300);
        assertNull(this.ending.get(), "one 'q' must only open the confirmation, never abort");
        assertTrue(this.screenThread.isAlive());

        this.terminal.addInput(new KeyStroke('y', false, false));
        this.waitForAnEnding();
        assertEquals(InstallProgressModel.Ending.ABORTED, this.ending.get());
    }

    @Test
    public void keepInstallingWhenTheAbortIsWavedOff() throws Exception {
        this.startScreen();

        this.terminal.addInput(new KeyStroke('q', false, false));
        this.terminal.addInput(new KeyStroke('n', false, false));
        Thread.sleep(300);
        assertNull(this.ending.get(), "'n' means carry on");
        assertTrue(this.screenThread.isAlive());
    }

    @Test
    public void handTheRunToTheBackgroundOnAcknowledgement() throws Exception {
        this.startScreen();

        this.terminal.addInput(new KeyStroke('b', false, false));
        Thread.sleep(200);
        assertNull(this.ending.get(), "'b' explains itself first -- it does not act immediately");

        this.terminal.addInput(new KeyStroke(KeyType.Enter));    // the < OK >
        this.waitForAnEnding();
        assertEquals(InstallProgressModel.Ending.BACKGROUNDED, this.ending.get());
    }

    @Test
    public void waitForAKeyOnceTheRunFinishedByItself() throws Exception {
        InstallProgressModel model = this.startScreen();

        model.runFinished(List.of(), InstallProgressModel.Ending.COMPLETED);
        Thread.sleep(300);
        assertTrue(this.screenThread.isAlive(),
                "an unattended install must not scroll its own result away");

        this.terminal.addInput(new KeyStroke(' ', false, false));
        this.waitForAnEnding();
        assertEquals(InstallProgressModel.Ending.COMPLETED, this.ending.get());
    }

    private InstallProgressModel startScreen() throws Exception {
        InstallProgressModel model = new InstallProgressModel();
        model.runStarting(3);
        model.stepStarting("clone-servers-manager", "Clone the ServersManager");
        model.stepFinished("clone-servers-manager", "Clone the ServersManager", StepOutcome.OK);
        model.stepStarting("build-spigot", "Build the Spigot server jars");
        model.operationStarted("Building 2 Spigot version(s) with BuildTools");
        model.taskQueued("spigot-1.8.8", "Spigot 1.8.8");
        model.taskQueued("spigot-1.20.4", "Spigot 1.20.4");
        model.taskStarted("spigot-1.8.8", "Spigot 1.8.8", System.currentTimeMillis());

        this.terminal = new DefaultVirtualTerminal(new TerminalSize(120, 40));
        Screen virtualScreen = new TerminalScreen(this.terminal);
        InstallProgressScreen screen = new InstallProgressScreen(model);

        this.screenThread = new Thread(() -> {
            try {
                this.ending.set(screen.runOn(virtualScreen));
            } catch (IOException ignored) {
                // the test always ends the loop through a key
            }
        }, "install-screen-under-test");
        this.screenThread.setDaemon(true);
        this.screenThread.start();
        Thread.sleep(200);      // let it paint a frame before any key arrives
        return model;
    }

    private void waitForAnEnding() throws InterruptedException {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (this.ending.get() != null) return;
            Thread.sleep(5);
        }
        fail("the screen never returned an ending");
    }
}
