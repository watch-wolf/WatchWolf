package dev.watchwolf.cli.log;

import dev.watchwolf.cli.io.NioFileGateway;
import dev.watchwolf.cli.layout.InstallLayout;
import dev.watchwolf.cli.layout.RuntimeFlavor;
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
 * The installer's own account of itself. The two properties that matter: it is on disk <em>as it
 * happens</em> (a run that is killed must still explain how far it got), and it never takes a
 * command down with it.
 */
public class RunLogShould {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-04T10:30:00Z"), ZoneOffset.UTC);

    @TempDir Path base;

    private InstallLayout layout() {
        return new InstallLayout(this.base, RuntimeFlavor.RELEASE);
    }

    @Test
    public void writeEachLineAsItHappensRatherThanAtTheEnd() throws IOException {
        RunLog log = RunLog.open(new NioFileGateway(), this.layout(), CLOCK, "build", List.of());
        log.line("[v] Cloning WatchWolf-ServersManager...");

        // deliberately NOT closed: an install killed half way through is exactly the case a log
        // exists for, and a buffer flushed on close would lose all of it
        String written = Files.readString(log.path());

        assertTrue(written.contains("watchwolf build"), written);
        assertTrue(written.contains("install base " + this.base), written);
        assertTrue(written.contains("Cloning WatchWolf-ServersManager"), written);
    }

    @Test
    public void nameTheFileAfterTheRunSoRunsDoNotOverwriteEachOther() {
        RunLog log = RunLog.open(new NioFileGateway(), this.layout(), CLOCK, "build", List.of());

        assertEquals("20260904-103000-build.log", log.path().getFileName().toString());
        assertEquals(this.layout().cliLogsDir(), log.path().getParent());
    }

    @Test
    public void keepTheHeaderLinesItWasGiven() throws IOException {
        RunLog log = RunLog.open(new NioFileGateway(), this.layout(), CLOCK, "build",
                List.of("branch       dev"));
        log.close();

        assertTrue(Files.readString(log.path()).contains("branch       dev"));
    }

    @Test
    public void setASectionApartFromTheRunningCommentary() throws IOException {
        RunLog log = RunLog.open(new NioFileGateway(), this.layout(), CLOCK, "build", List.of());
        log.section("plan", List.of("branch                 dev", "parallel builders      4"));
        log.close();

        String written = Files.readString(log.path());
        assertTrue(written.contains("\nplan\n    branch                 dev"), written);
        assertTrue(written.contains("finished     2026-09-04T10:30:00Z"), written);
    }

    @Test
    public void writeAnAttachmentBesideTheRunItBelongsTo() throws IOException {
        RunLog log = RunLog.open(new NioFileGateway(), this.layout(), CLOCK, "build", List.of());

        Path attachment = log.attachment("spigot-1.8.8",
                List.of("Loading BuildTools", "BUILD FAILED")).orElseThrow();

        // the shared timestamp is the point: in a bundle holding several runs, an attachment has
        // to be unambiguously part of one of them
        assertEquals("20260904-103000-spigot-1.8.8.log", attachment.getFileName().toString());
        assertEquals(log.path().getParent(), attachment.getParent());
        assertEquals("Loading BuildTools\nBUILD FAILED\n", Files.readString(attachment));
        assertTrue(Files.readString(log.path()).contains("2 line(s) of spigot-1.8.8 output"),
                "the log itself has to point at it, or nobody will find it");
    }

    @Test
    public void haveNoAttachmentToGiveWhenDisabled() {
        assertTrue(RunLog.disabled().attachment("spigot-1.8.8", List.of("nope")).isEmpty());
    }

    @Test
    public void keepOnlyTheNewestRuns() throws IOException {
        Path directory = this.layout().cliLogsDir();
        Files.createDirectories(directory);
        for (int i = 1; i <= 25; i++) {
            Path old = directory.resolve(String.format("202601%02d-000000-build.log", i));
            Files.writeString(old, "old run " + i + "\n");
            Files.setLastModifiedTime(old,
                    java.nio.file.attribute.FileTime.fromMillis(1_000_000L + i * 1000L));
        }

        RunLog log = RunLog.open(new NioFileGateway(), this.layout(), CLOCK, "build", List.of());
        log.close();

        try (var entries = Files.list(directory)) {
            assertEquals(20, entries.count(),
                    "a machine that installs often should not grow a heap of logs");
        }
        assertTrue(Files.exists(log.path()), "the run being logged is never the one pruned");
    }

    @Test
    public void carryOnWhenTheDirectoryCannotBeCreated() throws IOException {
        // .watchwolf/ is a plain file here, so creating run-logs/ under it cannot work -- standing
        // in for any unwritable install base. Losing the log must never cost the install.
        Files.writeString(this.layout().stateDir(), "not a directory");

        RunLog log = RunLog.open(new NioFileGateway(), this.layout(), CLOCK, "build", List.of());

        assertFalse(log.isEnabled());
        assertDoesNotThrow(() -> {
            log.line("something happened");
            log.section("plan", List.of("branch dev"));
            log.close();
        });
    }

    @Test
    public void doNothingAtAllWhenDisabled() {
        RunLog log = RunLog.disabled();

        assertFalse(log.isEnabled());
        assertDoesNotThrow(() -> {
            log.line("ignored");
            log.close();
        });
    }
}
