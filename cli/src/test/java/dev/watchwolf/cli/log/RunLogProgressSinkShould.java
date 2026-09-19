package dev.watchwolf.cli.log;

import dev.watchwolf.cli.io.NioFileGateway;
import dev.watchwolf.cli.layout.InstallLayout;
import dev.watchwolf.cli.layout.RuntimeFlavor;
import dev.watchwolf.cli.progress.ProgressSink;
import dev.watchwolf.cli.progress.RecordingProgressSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What ends up in the file, and -- just as important -- what does not: three hours of Spigot
 * heartbeats would bury the twenty lines somebody actually needs.
 */
public class RunLogProgressSinkShould {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-04T10:30:00Z"), ZoneOffset.UTC);

    @TempDir Path base;

    private RunLog log;
    private RecordingProgressSink delegate;
    private ProgressSink sink;

    @BeforeEach
    void setUp() {
        InstallLayout layout = new InstallLayout(this.base, RuntimeFlavor.RELEASE);
        this.log = RunLog.open(new NioFileGateway(), layout, CLOCK, "build", List.of());
        this.delegate = new RecordingProgressSink();
        this.sink = new RunLogProgressSink(this.delegate, this.log);
    }

    private String written() throws IOException {
        return Files.readString(this.log.path());
    }

    @Test
    public void recordStateChangesAndPassThemOn() throws IOException {
        this.sink.begin("Cloning WatchWolf-ServersManager from github.com");
        this.sink.warn("watchwolf.dev did not answer in 10s; retrying (2/3)");
        this.sink.end("done");

        String written = this.written();
        assertTrue(written.contains("[v] Cloning WatchWolf-ServersManager from github.com..."),
                written);
        assertTrue(written.contains("[w] watchwolf.dev did not answer"), written);
        assertTrue(written.contains("[v]   ...done"), written);

        // the wrapped sink still renders exactly as it did before being wrapped
        assertEquals(List.of("Cloning WatchWolf-ServersManager from github.com"),
                this.delegate.textOf("begin"));
        assertEquals(List.of("done"), this.delegate.textOf("end"));
    }

    @Test
    public void leaveTheHeartbeatsOutOfTheFile() throws IOException {
        this.sink.begin("Building 3 Spigot version(s)");
        for (int i = 0; i < 100; i++) {
            this.sink.update("0/3 done, 2 building, " + i + "m elapsed", 0, 3);
            this.sink.taskUpdate("spigot-1.8.8", "Spigot 1.8.8", "Applying patches", -1, -1);
        }

        String written = this.written();
        assertFalse(written.contains("elapsed"), "per-second updates belong on screen, not on disk");
        assertFalse(written.contains("Applying patches"), written);
        // ...but the screen still gets every one of them
        assertEquals(100, this.delegate.textOf("update").size());
    }

    @Test
    public void writeDetailEvenWhenTheScreenIsNotShowingIt() throws IOException {
        // the delegate here records everything, but the real plain sink drops detail without
        // --verbose; the file is read after something went wrong, when detail is what was missing
        this.sink.detail("starting Spigot_build_1.8.8 on eclipse-temurin:8-jdk");

        assertTrue(this.written().contains("starting Spigot_build_1.8.8"), this.written());
    }

    @Test
    public void recordEachJarsOwnOutcome() throws IOException {
        this.sink.taskQueued("spigot-1.20.4", "Spigot 1.20.4");
        this.sink.taskStarted("spigot-1.8.8", "Spigot 1.8.8");
        this.sink.taskFinished("spigot-1.8.8", "Spigot 1.8.8", "built", true);
        this.sink.taskFinished("spigot-1.20.4", "Spigot 1.20.4", "no jar was produced", false);

        String written = this.written();
        assertTrue(written.contains("Spigot 1.20.4: queued"), written);
        assertTrue(written.contains("[v]   Spigot 1.8.8: built"), written);
        assertTrue(written.contains("[e]   Spigot 1.20.4: no jar was produced"), written);
    }
}
