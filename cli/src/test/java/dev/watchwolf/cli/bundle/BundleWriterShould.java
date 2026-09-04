package dev.watchwolf.cli.bundle;

import dev.watchwolf.cli.docker.RootHelperConfig;
import dev.watchwolf.cli.fake.FakeDockerFacade;
import dev.watchwolf.cli.io.NioFileGateway;
import dev.watchwolf.cli.layout.InstallLayout;
import dev.watchwolf.cli.layout.RuntimeFlavor;
import dev.watchwolf.cli.net.HostInterfaces;
import dev.watchwolf.cli.progress.ProgressSink;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit-level coverage of {@link BundleWriter} against {@link FakeDockerFacade} and a real (but
 * throwaway, {@code @TempDir}) install tree -- no daemon, no network. The parts that need a real
 * container ({@link Confidence} groups, real logs) are covered by
 * {@code ITLogBundleWriterShould} instead; this class is about the archive's own contract.
 */
public class BundleWriterShould {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-03T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path base;

    private InstallLayout layout() {
        return new InstallLayout(this.base, RuntimeFlavor.RELEASE);
    }

    private BundleWriter writer() {
        return new BundleWriter(new FakeDockerFacade(), new NioFileGateway(), this.layout(),
                new HostInterfaces(), CLOCK);
    }

    private static List<String> entryNames(Path archive) throws IOException {
        List<String> names = new ArrayList<>();
        try (InputStream in = Files.newInputStream(archive);
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(in);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) names.add(entry.getName());
        }
        return names;
    }

    private static String readEntry(Path archive, String name) throws IOException {
        try (InputStream in = Files.newInputStream(archive);
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(in);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (entry.getName().equals(name)) {
                    return new String(tar.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    @Test
    public void writeTheFixedManifestSectionsEvenWithAnEmptyInstall() throws IOException {
        Path destination = this.base.resolve("empty.tar.gz");
        this.writer().write(destination, BundleWriter.Selection.everything(), ProgressSink.discarding());

        List<String> names = entryNames(destination);
        assertTrue(names.contains("manifest.txt"));
        assertTrue(names.contains("environment.txt"));
        assertTrue(names.contains("containers.txt"));
        assertTrue(names.contains("network.txt"));
        assertTrue(names.contains("artefacts.txt"));
    }

    @Test
    public void createTheDestinationDirectoryWhenItDoesNotExistYet() throws IOException {
        // callers point this at <install base>/logs/, which is not guaranteed to exist yet --
        // e.g. a fresh install that has never run `watchwolf logs` before
        Path destination = this.base.resolve("logs").resolve("fresh-install.tar.gz");
        assertFalse(Files.isDirectory(destination.getParent()));

        this.writer().write(destination, BundleWriter.Selection.everything(), ProgressSink.discarding());

        assertTrue(Files.exists(destination));
    }

    @Test
    public void neverIncludeAJarEvenWhenOneIsPresent() throws IOException {
        InstallLayout layout = this.layout();
        Files.createDirectories(layout.serverTypes("Spigot"));
        Files.write(layout.serverJar("Spigot", "1.8.8"), "not really a jar".getBytes());

        Path destination = this.base.resolve("with-jar.tar.gz");
        this.writer().write(destination, BundleWriter.Selection.everything(), ProgressSink.discarding());

        assertTrue(entryNames(destination).stream().noneMatch(name -> name.endsWith(".jar")),
                "server-types/ must be inventoried by name and size only, never copied");
        String artefacts = readEntry(destination, "artefacts.txt");
        assertTrue(artefacts.contains("1.8.8.jar"), "the inventory must still name it");
    }

    @Test
    public void truncateAnOversizedFileAndKeepItsTail() throws IOException {
        InstallLayout layout = this.layout();
        Files.createDirectories(layout.logs("1772387923303"));
        Files.writeString(layout.sessionInfoFile("1772387923303"), "serverType = Spigot\n");

        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 50_000; i++) huge.append("line ").append(i).append(' ').append("x".repeat(60)).append('\n');
        assertTrue(huge.length() > 2 * 1024 * 1024, "fixture must exceed the 2MB cap");
        Files.writeString(layout.sessionLogFile("1772387923303"), huge.toString());

        Path destination = this.base.resolve("truncated.tar.gz");
        this.writer().write(destination, BundleWriter.Selection.everything(), ProgressSink.discarding());

        String contents = readEntry(destination, "sessions/1772387923303/latest.log");
        assertNotNull(contents);
        assertTrue(contents.startsWith("[truncated"));
        assertTrue(contents.contains("line 49999"), "the tail must be kept");
        assertFalse(contents.contains("line 0 "), "the head must be dropped");
    }

    @Test
    public void collectTheCliOwnRunLogs() throws IOException {
        // the bundle used to describe everything except the run that produced it, which is one of
        // the two things bundles are opened for
        InstallLayout layout = this.layout();
        Files.createDirectories(layout.cliLogsDir());
        Files.writeString(layout.cliLogsDir().resolve("20260903-100000-build.log"),
                "watchwolf build\n[e]   -> FAILED\n");
        Files.createDirectories(layout.stateDir());
        Files.writeString(layout.lastRunFile(), "ending: backgrounded\nsummary: install failed\n");

        Path destination = this.base.resolve("with-run-logs.tar.gz");
        this.writer().write(destination, BundleWriter.Selection.everything(),
                ProgressSink.discarding());

        List<String> names = entryNames(destination);
        assertTrue(names.contains("cli-runs/20260903-100000-build.log"), names.toString());
        assertTrue(names.contains("cli-runs/last-run.txt"), names.toString());
        assertTrue(readEntry(destination, "cli-runs/20260903-100000-build.log")
                .contains("-> FAILED"));
    }

    @Test
    public void keepOnlyTheNewestRunLogsAndSayHowManyWereLeftOut() throws IOException {
        InstallLayout layout = this.layout();
        Files.createDirectories(layout.cliLogsDir());
        for (int i = 1; i <= 12; i++) {
            Path log = layout.cliLogsDir().resolve(String.format("2026090%d-100000-build.log", 0));
            log = layout.cliLogsDir().resolve("run-" + String.format("%02d", i) + ".log");
            Files.writeString(log, "run " + i + "\n");
            Files.setLastModifiedTime(log,
                    java.nio.file.attribute.FileTime.fromMillis(1_000_000L + i * 1000L));
        }

        Path destination = this.base.resolve("many-run-logs.tar.gz");
        this.writer().write(destination, BundleWriter.Selection.everything(),
                ProgressSink.discarding());

        List<String> names = entryNames(destination);
        long collected = names.stream().filter(name -> name.startsWith("cli-runs/")).count();
        assertEquals(10, collected, "a machine that installs often must not blow up the bundle");
        assertTrue(names.contains("cli-runs/run-12.log"), "the newest run is the one that matters");
        assertFalse(names.contains("cli-runs/run-01.log"));
        assertTrue(readEntry(destination, "manifest.txt").contains("2 older run(s)"),
                "a gap that is explained is useful; a silent one is misleading");
    }

    @Test
    public void recordWhatWasSkippedAndWhy() throws IOException {
        Path destination = this.base.resolve("skips.tar.gz");
        this.writer().write(destination, BundleWriter.Selection.everything(), ProgressSink.discarding());

        String manifest = readEntry(destination, "manifest.txt");
        assertTrue(manifest.contains("containers/ClientsManager.log"));
        assertTrue(manifest.contains("no such container"),
                "a skip must say why, not just that it happened");
    }

    @Test
    public void filterSessionsBySelection() throws IOException {
        InstallLayout layout = this.layout();
        for (String id : List.of("1000000000001", "1000000000002", "1000000000003")) {
            Files.createDirectories(layout.logs(id));
            Files.writeString(layout.sessionInfoFile(id), "serverType = Spigot\n");
        }

        Path destination = this.base.resolve("last-two.tar.gz");
        this.writer().write(destination, BundleWriter.Selection.last(2), ProgressSink.discarding());

        List<String> names = entryNames(destination);
        assertTrue(names.contains("sessions/1000000000003/info.txt"));
        assertTrue(names.contains("sessions/1000000000002/info.txt"));
        assertFalse(names.contains("sessions/1000000000001/info.txt"),
                "--last 2 must keep only the newest two sessions");
    }

    @Test
    public void fallBackGracefullyWhenLogsIsUnreadableAndNoRootHelperIsConfigured() throws IOException {
        // POSIX permission checks apply to the owner too (unlike some other platforms), so this
        // genuinely makes the directory unreadable to this very test process without needing a
        // container -- a cheap way to unit-test the fallback path that ITLogBundleWriterShould's
        // recoverASessionFromALogsDirectoryThisUserCannotRead exercises for real with a helper.
        assumeTrue(isPosix(), "POSIX permissions are required for this fixture");

        InstallLayout layout = this.layout();
        Files.createDirectories(layout.logs("1772387923303"));
        Files.writeString(layout.sessionInfoFile("1772387923303"), "serverType = Spigot\n");
        Files.setPosixFilePermissions(layout.logs(), Set.of());   // ---------- : unreadable, even to us

        try {
            BundleWriter writer = new BundleWriter(new FakeDockerFacade(), new NioFileGateway(),
                    layout, new HostInterfaces(), CLOCK,
                    new RootHelperConfig(null, null, null));   // not run through the launcher

            Path destination = this.base.resolve("unreadable.tar.gz");
            // must not throw -- graceful degradation, not a crash
            writer.write(destination, BundleWriter.Selection.everything(), ProgressSink.discarding());

            assertTrue(entryNames(destination).contains("manifest.txt"));
            assertFalse(entryNames(destination).contains("sessions/1772387923303/info.txt"),
                    "with no root helper available, the unreadable session cannot be recovered");
        } finally {
            // restore permissions so @TempDir cleanup can delete it
            Files.setPosixFilePermissions(layout.logs(),
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE));
        }
    }

    private static boolean isPosix() {
        return java.nio.file.FileSystems.getDefault()
                .supportedFileAttributeViews().contains("posix");
    }
}
