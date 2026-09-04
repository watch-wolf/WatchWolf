package dev.watchwolf.cli.bundle;

import dev.watchwolf.cli.docker.DockerJavaFacade;
import dev.watchwolf.cli.docker.RootHelperConfig;
import dev.watchwolf.cli.docker.RunSpec;
import dev.watchwolf.cli.io.NioFileGateway;
import dev.watchwolf.cli.layout.InstallLayout;
import dev.watchwolf.cli.layout.RuntimeFlavor;
import dev.watchwolf.cli.net.HostInterfaces;
import dev.watchwolf.cli.progress.ProgressSink;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves {@link BundleWriter} against a real container's real log output and a fabricated install
 * tree, over an actual {@code tar.gz} on disk -- the parts {@code BundleWriterShould}'s fakes
 * cannot prove: that the archive really opens, and that the size cap and the jar exclusion survive
 * a real gzip/tar round trip.
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
public class ITLogBundleWriterShould {
    /** Matches ContainerNames.isMcServer, so the writer actually looks for this container. */
    private static final String SESSION_ID = "1772387923303";

    private static DockerJavaFacade docker;
    private static String containerName;

    @TempDir
    static Path base;

    @BeforeAll
    static void startContainer() {
        docker = DockerJavaFacade.connect();
        if (!docker.imageExists("alpine:latest")) {
            docker.pullImage("alpine:latest", ProgressSink.discarding());
        }

        containerName = "MC_Server-" + SESSION_ID;
        docker.runDetached(RunSpec.of("alpine:latest")
                .named(containerName)
                .withEntrypoint("sh", "-c")
                .withCommand("echo bundle-writer-integration-marker; sleep 60")
                .autoRemove(true));
    }

    @AfterAll
    static void stopContainer() {
        if (docker == null) return;
        try {
            if (containerName != null) docker.stopContainer(containerName, 5);
        } finally {
            docker.close();
        }
    }

    private InstallLayout layout() {
        return new InstallLayout(base, RuntimeFlavor.RELEASE);
    }

    private void fabricateInstallTree() throws IOException {
        InstallLayout layout = this.layout();
        Files.createDirectories(layout.serverTypes("Spigot"));
        Files.createDirectories(layout.usualPlugins());
        Files.createDirectories(layout.logs(SESSION_ID));

        // a jar-shaped file: must be INVENTORIED, never copied into the bundle
        Files.write(layout.serverTypes("Spigot").resolve("1.8.8.jar"),
                "not a real jar, just needs a size".repeat(1000).getBytes(StandardCharsets.UTF_8));
        Files.writeString(layout.sessionInfoFile(SESSION_ID),
                "serverType = Spigot\nserverVersion = 1.8.8\n");

        // oversized on purpose (BundleWriter caps a file at 2MB): proves the truncation-and-tail
        // behaviour survives a real tar/gzip round trip, not just the in-memory model. Each line
        // is padded well past its "line N" content so a modest line count clears the cap easily.
        StringBuilder hugeLog = new StringBuilder();
        for (int i = 0; i < 100_000; i++) {
            hugeLog.append("line ").append(i).append(' ').append("x".repeat(50)).append('\n');
        }
        assertTrue(hugeLog.length() > 2 * 1024 * 1024, "fixture must exceed the 2MB cap");
        Files.writeString(layout.sessionLogFile(SESSION_ID), hugeLog.toString());
    }

    @Test
    void produceAnArchiveThatActuallyOpens() throws IOException {
        this.fabricateInstallTree();
        Path destination = base.resolve("bundle.tar.gz");

        new BundleWriter(docker, new NioFileGateway(), this.layout(), new HostInterfaces(),
                Clock.systemUTC())
                .write(destination, BundleWriter.Selection.everything(), ProgressSink.discarding());

        assertTrue(Files.exists(destination));
        assertTrue(Files.size(destination) > 0);

        List<String> entryNames = new ArrayList<>();
        try (InputStream in = Files.newInputStream(destination);
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(in);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) entryNames.add(entry.getName());
        }

        assertTrue(entryNames.contains("manifest.txt"));
        assertTrue(entryNames.contains("environment.txt"));
        assertTrue(entryNames.contains("containers.txt"));
        assertTrue(entryNames.contains("artefacts.txt"));

        // never sweep up jars -- server-types/ and usual-plugins/ can be hundreds of MB
        assertTrue(entryNames.stream().noneMatch(name -> name.endsWith(".jar")),
                "the archive must never contain a jar; entries were: " + entryNames);
    }

    @Test
    void includeARealContainersLogs() throws IOException {
        this.fabricateInstallTree();
        Path destination = base.resolve("bundle-with-logs.tar.gz");

        // the container needs a moment to have written its startup line
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
                && docker.logs(containerName, 10).isEmpty()) {
            sleep(200);
        }

        new BundleWriter(docker, new NioFileGateway(), this.layout(), new HostInterfaces(),
                Clock.systemUTC())
                .write(destination, BundleWriter.Selection.everything(), ProgressSink.discarding());

        String containersLogEntry = "containers/" + containerName + ".log";
        String contents = readEntry(destination, containersLogEntry);
        assertNotNull(contents, "expected an entry named " + containersLogEntry);
        assertTrue(contents.contains("bundle-writer-integration-marker"), "log was: " + contents);
    }

    @Test
    void truncateAnOversizedLogAndKeepItsTail() throws IOException {
        this.fabricateInstallTree();
        Path destination = base.resolve("bundle-truncated.tar.gz");

        new BundleWriter(docker, new NioFileGateway(), this.layout(), new HostInterfaces(),
                Clock.systemUTC())
                .write(destination, BundleWriter.Selection.everything(), ProgressSink.discarding());

        String contents = readEntry(destination, "sessions/" + SESSION_ID + "/latest.log");
        assertNotNull(contents);
        assertTrue(contents.startsWith("[truncated"), "expected a truncation notice, got: "
                + contents.substring(0, Math.min(80, contents.length())));
        assertTrue(contents.contains("line 99999"), "the TAIL must be kept, not the head");
        assertFalse(contents.contains("line 0\n"), "the head should have been dropped");
    }

    /**
     * Proves the root-helper path in {@code readableLogsRoot()}: when {@code logs/} is genuinely
     * unreadable by this process's uid (not just "usually readable in practice", which is what
     * every other test here exercises), the bundle must still recover the session instead of
     * silently reporting zero sessions found.
     */
    @Test
    void recoverASessionFromALogsDirectoryThisUserCannotRead() throws IOException {
        InstallLayout layout = this.layout();
        Files.createDirectories(layout.logs());

        // Fabricate the pathological case with a root helper of our own: write a session, then
        // chmod both the session directory AND logs/ itself down to owner-only. A plain
        // Files.createDirectories from this JVM cannot produce a directory this JVM cannot read
        // (whatever umask it uses, our own uid is still the owner), so this has to run as a
        // different uid to be a real test of the fallback rather than a tautology.
        String sessionId = "1772387923304";
        int exitCode = docker.runToCompletion(
                RunSpec.of("alpine:latest")
                        .asUser("0")
                        .bind(layout.logs().toString(), "/logs")
                        .withEntrypoint("sh", "-c")
                        .withCommand(
                                "mkdir -p /logs/" + sessionId
                                        + " && echo 'serverType = Spigot' > /logs/" + sessionId + "/info.txt"
                                        + " && echo 'serverVersion = 1.8.8' >> /logs/" + sessionId + "/info.txt"
                                        + " && chmod 700 /logs/" + sessionId + " /logs"),
                null);
        assertEquals(0, exitCode, "fabricating the root-owned fixture failed");

        // Sanity check the fixture actually did what it claims. This can legitimately be true
        // (not just "should never happen"): ci/tests.sh --integration already runs this whole
        // suite inside one Docker container, and that container's own filesystem is not itself
        // bind-mounted at a host-correspondent path -- so a *second*, sibling container reached
        // through the socket (the alpine fixture above) cannot see this JVM's @TempDir at all,
        // and silently gets an empty directory auto-created at that literal path on the real
        // host instead. That is a structural limit of running this one test nested inside the
        // dockerized CI wrapper, not a bug in the fallback being tested -- so skip rather than
        // fail, the same way MinecraftJavaVersionsMatchesCoreShould skips when its own
        // prerequisite (a sibling checkout) is not reachable from inside that same wrapper. Run
        // this class directly (mvn -P integration-test -Dit.test=ITLogBundleWriterShould, outside
        // ci/tests.sh) on a real host to exercise it for real.
        Assumptions.assumeTrue(!Files.isReadable(layout.logs()),
                "Could not produce a genuinely unreadable logs/ from inside this test runner -- "
                        + "see this method's comment for why. Skipping rather than failing.");

        // This is the launcher's own tag ("watchwolf-cli:local" -- no namespace, never a
        // published image; see cli/watchwolf's header for why). Nothing in ci/tests.sh builds it,
        // so it is only present when someone has separately run `ci/build.sh --image` or the
        // launcher itself; skip rather than fail when it is not there.
        String image = "watchwolf-cli:local";
        Assumptions.assumeTrue(docker.imageExists(image),
                "The '" + image + "' image is not built. Run 'bash ci/build.sh --image', or "
                        + "'./watchwolf build' once, then re-run this test.");

        Path destination = base.resolve("bundle-recovered.tar.gz");
        try {
            RootHelperConfig helper = new RootHelperConfig(
                    image, String.valueOf(unixUid()), String.valueOf(unixGid()));

            new BundleWriter(docker, new NioFileGateway(), layout, new HostInterfaces(),
                    Clock.systemUTC(), helper)
                    .write(destination, BundleWriter.Selection.everything(), ProgressSink.discarding());

            String info = readEntry(destination, "sessions/" + sessionId + "/info.txt");
            assertNotNull(info, "the root-owned session should have been recovered through the "
                    + "helper, not silently skipped");
            assertTrue(info.contains("Spigot"), "info.txt was: " + info);
        } finally {
            // chmod back so @TempDir cleanup (running as us, not root) can actually delete it
            docker.runToCompletion(
                    RunSpec.of("alpine:latest").asUser("0")
                            .bind(layout.logs().toString(), "/logs")
                            .withEntrypoint("sh", "-c")
                            .withCommand("chmod -R 755 /logs"),
                    null);
        }
    }

    private static int unixUid() {
        try {
            return Integer.parseInt(runId("-u"));
        } catch (Exception ex) {
            return 0;
        }
    }

    private static int unixGid() {
        try {
            return Integer.parseInt(runId("-g"));
        } catch (Exception ex) {
            return 0;
        }
    }

    private static String runId(String flag) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("id", flag).start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).strip();
        process.waitFor();
        return output;
    }

    private static String readEntry(Path archive, String entryName) throws IOException {
        try (InputStream in = Files.newInputStream(archive);
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(in);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!entry.getName().equals(entryName)) continue;
                return new String(tar.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
