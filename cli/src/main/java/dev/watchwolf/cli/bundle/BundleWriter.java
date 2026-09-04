package dev.watchwolf.cli.bundle;

import dev.watchwolf.cli.docker.ContainerSnapshot;
import dev.watchwolf.cli.docker.DaemonInfo;
import dev.watchwolf.cli.docker.DockerFacade;
import dev.watchwolf.cli.docker.RootHelperConfig;
import dev.watchwolf.cli.docker.RunSpec;
import dev.watchwolf.cli.io.FileGateway;
import dev.watchwolf.cli.layout.InstallLayout;
import dev.watchwolf.cli.net.AddressCandidate;
import dev.watchwolf.cli.net.HostInterfaces;
import dev.watchwolf.cli.parse.ContainerNames;
import dev.watchwolf.cli.progress.ProgressSink;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Writes one {@code tar.gz} answering "what was this machine doing when it broke".
 *
 * <p>Three rules it will not break:
 *
 * <ul>
 *   <li><b>Never sweep up jars.</b> {@code server-types/} and {@code usual-plugins/} hold hundreds
 *       of megabytes; they are inventoried by name and size, never copied.</li>
 *   <li><b>Cap each file and keep the tail</b>, annotating the truncation in the entry itself, so
 *       one runaway {@code latest.log} cannot make the bundle unusable.</li>
 *   <li><b>Say what was skipped and why</b> in {@code manifest.txt}. A gap that is explained is
 *       useful; a gap that is silent is misleading.</li>
 * </ul>
 */
public final class BundleWriter {
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int MAX_LOG_LINES = 5000;

    /** Recent runs are what a bug report is about; the rest are on the machine if ever needed. */
    private static final int MAX_RUN_LOGS = 10;

    private final DockerFacade docker;
    private final FileGateway files;
    private final InstallLayout layout;
    private final HostInterfaces interfaces;
    private final Clock clock;
    private final RootHelperConfig rootHelper;

    /**
     * A copy of logs/, owned by us, made once per {@link #write} if the real one turns out to be
     * root-owned and unreadable. {@code null} until an attempt has actually been made -- see
     * {@link #readableLogsRoot()}.
     */
    private Path scratchLogsRoot;
    private boolean scratchLogsAttempted;

    public BundleWriter(DockerFacade docker, FileGateway files, InstallLayout layout,
                        HostInterfaces interfaces, Clock clock) {
        this(docker, files, layout, interfaces, clock, RootHelperConfig.fromEnvironment());
    }

    public BundleWriter(DockerFacade docker, FileGateway files, InstallLayout layout,
                        HostInterfaces interfaces, Clock clock, RootHelperConfig rootHelper) {
        this.docker = docker;
        this.files = files;
        this.layout = layout;
        this.interfaces = interfaces;
        this.clock = clock;
        this.rootHelper = rootHelper;
    }

    /** Which sessions to include. */
    public record Selection(List<String> sessionIds, int lastN, Duration since) {
        public static Selection everything() {
            return new Selection(List.of(), -1, null);
        }

        public static Selection last(int n) {
            return new Selection(List.of(), n, null);
        }

        public static Selection sessions(List<String> ids) {
            return new Selection(List.copyOf(ids), -1, null);
        }

        public static Selection since(Duration duration) {
            return new Selection(List.of(), -1, duration);
        }
    }

    public Path write(Path destination, Selection selection, ProgressSink progress) {
        this.scratchLogsRoot = null;
        this.scratchLogsAttempted = false;

        ManifestBuilder manifest = new ManifestBuilder(this.clock.instant());
        progress.begin("Collecting diagnostics into " + destination.getFileName());

        try {
            Path parent = destination.getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IOException ex) {
            progress.end("failed");
            throw new BundleFailedException(
                    "Could not create " + destination.getParent() + " to write the bundle into",
                    "Check the install base is writable, or pass --out with another path.", ex);
        }

        try (OutputStream out = Files.newOutputStream(destination);
             GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(out);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {

            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

            this.addEnvironment(tar, manifest);
            this.addContainerInventory(tar, manifest);
            this.addHostNetwork(tar, manifest);
            this.addContainerLogs(tar, manifest);
            this.addCliRunLogs(tar, manifest);
            this.addSessions(tar, manifest, selection);
            this.addArtefactInventory(tar, manifest);

            // last, so it describes everything above it
            this.addEntry(tar, "manifest.txt", manifest.render());

        } catch (IOException ex) {
            progress.end("failed");
            throw new BundleFailedException(
                    "Could not write the diagnostics bundle to " + destination,
                    "Check the directory is writable, then try --out with another path.", ex);
        }

        progress.end("wrote " + destination);
        return destination;
    }

    /**
     * {@code logs/}, or a copy of it we can actually read.
     *
     * <p>{@code logs/<id>/} is written by the ServersManager container, which runs as root; on
     * most images that still leaves it world-readable (mode 755 / 644), but nothing guarantees it,
     * and when it doesn't this is the fallback. Scoped to {@code logs/} specifically, and not
     * {@code tmp/}: {@code logs/} structurally only ever holds {@code info.txt} and
     * {@code latest.log}, so copying the whole tree can never violate the "never sweep up jars"
     * rule the way copying {@code tmp/<id>/} (which holds {@code server.jar} and the plugin jars)
     * would. {@code tmp/}'s four named config files keep their existing per-file graceful skip
     * instead of gaining this treatment, precisely so that invariant is never at risk here.
     *
     * <p>The copy happens through {@link dev.watchwolf.cli.command.InternalCopyCommand}, run as
     * {@code --user 0} in a short-lived container from this CLI's own image -- the daemon socket is
     * already root-equivalent, so this is the honest way to use the privilege we demonstrably have,
     * rather than asking for {@code sudo} on top of it. Attempted at most once per {@link #write},
     * and only when the real directory turns out to need it.
     */
    private Path readableLogsRoot() {
        Path real = this.layout.logs();
        if (this.files.isDirectory(real) && this.files.isReadable(real)
                && !this.files.list(real).isEmpty()) {
            return real;
        }
        // an empty listing is ambiguous -- genuinely no sessions yet, or unreadable -- so only
        // pay for the container spin-up once, and only if it has not already been tried
        if (this.scratchLogsAttempted) {
            return this.scratchLogsRoot != null ? this.scratchLogsRoot : real;
        }
        this.scratchLogsAttempted = true;
        if (!this.files.isDirectory(real)) return real;   // truly nothing to copy
        if (!this.rootHelper.isAvailable()) {
            return real;   // not run through the launcher (e.g. a unit test); nothing we can do
        }

        Path scratch = this.layout.stateDir().resolve("root-readable-logs");
        try {
            this.files.createDirectories(scratch);
        } catch (IOException ex) {
            return real;
        }

        try {
            int exitCode = this.docker.runToCompletion(
                    RunSpec.of(this.rootHelper.image())
                            .asUser("0")
                            .bind(real.toString(), "/src")
                            .bind(scratch.toString(), "/dst")
                            .withCommand("internal-copy", "/src", "/dst",
                                    "--uid", this.rootHelper.uid(), "--gid", this.rootHelper.gid()),
                    null);
            if (exitCode != 0) return real;
        } catch (RuntimeException ex) {
            return real;   // best-effort: the per-file skip messages still explain the gap
        }

        this.scratchLogsRoot = scratch;
        return scratch;
    }

    // ---- sections --------------------------------------------------------------------------

    private void addEnvironment(TarArchiveOutputStream tar, ManifestBuilder manifest)
            throws IOException {
        DaemonInfo daemon = this.docker.daemonInfo();
        StringBuilder text = new StringBuilder();
        text.append("collected-at    = ").append(this.clock.instant()).append('\n');
        text.append("install-base    = ").append(this.layout.base()).append('\n');
        text.append("runtime-flavour = ").append(this.layout.flavor().directoryName()).append('\n');
        text.append("docker-server   = ").append(daemon.serverVersion()).append('\n');
        text.append("docker-api      = ").append(daemon.apiVersion()).append('\n');
        text.append("docker-platform = ").append(daemon.platformName()).append('\n');
        text.append("cli-version     = ")
            .append(Optional.ofNullable(BundleWriter.class.getPackage().getImplementationVersion())
                    .orElse("(development build)")).append('\n');

        this.addEntry(tar, "environment.txt", text.toString());
        manifest.collected("environment.txt", "versions and install layout");
    }

    private void addContainerInventory(TarArchiveOutputStream tar, ManifestBuilder manifest)
            throws IOException {
        StringBuilder text = new StringBuilder();
        text.append(String.format("%-28s %-34s %-10s %s%n",
                "NAME", "IMAGE", "STATE", "PUBLISHED PORTS"));

        for (ContainerSnapshot container : this.docker.listContainers()) {
            // published bindings are the point: they say whether 8000/7000 are reachable at all
            text.append(String.format("%-28s %-34s %-10s %s%n",
                    container.name(), container.image(), container.state(), container.ports()));
        }

        this.addEntry(tar, "containers.txt", text.toString());
        manifest.collected("containers.txt", "container inventory with published port bindings");
    }

    private void addHostNetwork(TarArchiveOutputStream tar, ManifestBuilder manifest)
            throws IOException {
        DaemonInfo daemon = this.docker.daemonInfo();
        StringBuilder text = new StringBuilder();

        // whose interfaces these are is the single most important caveat in the bundle
        if (daemon.reachable() && !daemon.hostNetworkingIsTruthful()) {
            text.append("VIEW: the CONTAINER's interfaces. This is Docker Desktop, where\n")
                .append("--network host does not expose the host's own adapters, so the list\n")
                .append("below does NOT describe the user's machine.\n\n");
            manifest.caveat("Network facts are the container's view, not the host's "
                    + "(Docker Desktop).");
        } else {
            text.append("VIEW: the HOST's interfaces (the CLI runs with --network host).\n\n");
        }

        text.append("advertised address (what the ServersManager will hand out): ")
            .append(this.interfaces.preferredMachineIp()).append("\n\ncandidates:\n");
        for (AddressCandidate candidate : this.interfaces.candidates()) {
            text.append("  ").append(candidate)
                .append(candidate.isSuspicious() ? "   <-- probably not reachable from elsewhere" : "")
                .append('\n');
        }

        this.addEntry(tar, "network.txt", text.toString());
        manifest.collected("network.txt", "host interfaces and the advertised address");
    }

    private void addContainerLogs(TarArchiveOutputStream tar, ManifestBuilder manifest)
            throws IOException {
        List<String> wanted = new ArrayList<>(
                List.of(ContainerNames.SERVERS_MANAGER, ContainerNames.CLIENTS_MANAGER));
        for (ContainerSnapshot container : this.docker.listContainers()) {
            if (ContainerNames.isMcServer(container.name())) wanted.add(container.name());
        }

        for (String name : wanted) {
            if (this.docker.findContainer(name).isEmpty()) {
                manifest.skipped("containers/" + name + ".log", "no such container");
                continue;
            }
            List<String> lines = this.docker.logs(name, MAX_LOG_LINES);
            if (lines.isEmpty()) {
                manifest.skipped("containers/" + name + ".log", "the container has no output");
                continue;
            }
            this.addEntry(tar, "containers/" + name + ".log", String.join("\n", lines) + "\n");
            manifest.collected("containers/" + name + ".log", lines.size() + " line(s)");
        }
    }

    /**
     * The CLI's own account of itself: {@code <base>/.watchwolf/run-logs/}, written by
     * {@link dev.watchwolf.cli.log.RunLog}.
     *
     * <p>The bundle used to describe everything except the run that produced it -- and "the
     * install went wrong" is one of the two things bundles are opened for. Includes the note left
     * by an install that was sent to the background, since that run's terminal output never
     * existed.
     */
    private void addCliRunLogs(TarArchiveOutputStream tar, ManifestBuilder manifest)
            throws IOException {
        Path directory = this.layout.cliLogsDir();
        if (!this.files.isDirectory(directory)) {
            manifest.skipped("cli-runs/", "no CLI run logs yet (" + directory + ")");
        } else {
            List<Path> logs = new ArrayList<>();
            for (Path entry : this.files.list(directory)) {
                if (entry.getFileName().toString().endsWith(".log")) logs.add(entry);
            }
            // newest first: the run being reported on is almost always the last one
            logs.sort(Comparator.comparing(this.files::lastModified).reversed());

            if (logs.size() > MAX_RUN_LOGS) {
                manifest.skipped("cli-runs/ (" + (logs.size() - MAX_RUN_LOGS) + " older run(s))",
                        "only the newest " + MAX_RUN_LOGS + " are collected");
                logs = logs.subList(0, MAX_RUN_LOGS);
            }
            for (Path log : logs) {
                this.addFileIfReadable(tar, manifest, log,
                        "cli-runs/" + log.getFileName());
            }
        }

        if (this.files.exists(this.layout.lastRunFile())) {
            this.addFileIfReadable(tar, manifest, this.layout.lastRunFile(),
                    "cli-runs/last-run.txt");
        }
    }

    private void addSessions(TarArchiveOutputStream tar, ManifestBuilder manifest,
                             Selection selection) throws IOException {
        List<String> sessions = this.selectSessions(selection, manifest);

        Path readableLogsRoot = this.readableLogsRoot();

        for (String sessionId : sessions) {
            this.addFileIfReadable(tar, manifest,
                    readableLogsRoot.resolve(sessionId).resolve("info.txt"),
                    "sessions/" + sessionId + "/info.txt");
            this.addFileIfReadable(tar, manifest,
                    readableLogsRoot.resolve(sessionId).resolve("latest.log"),
                    "sessions/" + sessionId + "/latest.log");

            // the generated server config, when the scratch folder still exists (it is deleted
            // when the server stops, so this is often absent -- and that is worth recording)
            Path scratch = this.layout.tmp(sessionId);
            if (!this.files.isDirectory(scratch)) {
                manifest.skipped("sessions/" + sessionId + "/config",
                        "tmp/" + sessionId + "/ no longer exists (deleted when the server stopped)");
                continue;
            }
            for (String name : List.of("server.properties", "bukkit.yml", "eula.txt",
                    "plugins/WatchWolf/config.yml")) {
                this.addFileIfReadable(tar, manifest, scratch.resolve(name),
                        "sessions/" + sessionId + "/" + name);
            }
        }
    }

    private List<String> selectSessions(Selection selection, ManifestBuilder manifest) {
        List<String> all = new ArrayList<>();
        for (Path entry : this.files.list(this.readableLogsRoot())) {
            String name = entry.getFileName().toString();
            if (name.matches("\\d+")) all.add(name);
        }
        all.sort((left, right) -> Long.compare(Long.parseLong(right), Long.parseLong(left)));

        if (!selection.sessionIds().isEmpty()) {
            List<String> chosen = new ArrayList<>();
            for (String wanted : selection.sessionIds()) {
                if (all.contains(wanted)) {
                    chosen.add(wanted);
                } else {
                    manifest.skipped("sessions/" + wanted, "no logs/" + wanted + "/ directory");
                }
            }
            return chosen;
        }

        if (selection.since() != null) {
            Instant cutoff = this.clock.instant().minus(selection.since());
            List<String> chosen = new ArrayList<>();
            for (String sessionId : all) {
                if (Instant.ofEpochMilli(Long.parseLong(sessionId)).isAfter(cutoff)) {
                    chosen.add(sessionId);
                }
            }
            return chosen;
        }

        if (selection.lastN() > 0) {
            return all.subList(0, Math.min(selection.lastN(), all.size()));
        }
        return all;
    }

    /** Names and sizes only. These directories hold hundreds of MB of jars. */
    private void addArtefactInventory(TarArchiveOutputStream tar, ManifestBuilder manifest)
            throws IOException {
        StringBuilder text = new StringBuilder();

        text.append("server-types/ (names and sizes only -- jars are never included)\n");
        for (Path typeDirectory : this.files.list(this.layout.serverTypes())) {
            if (!this.files.isDirectory(typeDirectory)) continue;
            for (Path jar : this.files.list(typeDirectory)) {
                text.append(String.format("  %-20s %-16s %10d bytes%n",
                        typeDirectory.getFileName(), jar.getFileName(), this.files.size(jar)));
            }
        }

        text.append("\nusual-plugins/ (names and sizes only)\n");
        for (Path jar : this.files.list(this.layout.usualPlugins())) {
            text.append(String.format("  %-44s %10d bytes%n",
                    jar.getFileName(), this.files.size(jar)));
        }

        this.addEntry(tar, "artefacts.txt", text.toString());
        manifest.collected("artefacts.txt", "server-types/ and usual-plugins/ inventory (no jars)");
    }

    // ---- plumbing --------------------------------------------------------------------------

    private void addFileIfReadable(TarArchiveOutputStream tar, ManifestBuilder manifest,
                                   Path source, String entryName) throws IOException {
        if (!this.files.exists(source)) {
            manifest.skipped(entryName, "not present");
            return;
        }
        if (!this.files.isReadable(source)) {
            // logs/ and tmp/ are written by the ServersManager container as root
            manifest.skipped(entryName, "not readable by this user (owned by root, written by the "
                    + "ServersManager container)");
            return;
        }

        long size = this.files.size(source);
        String contents;
        boolean truncated = false;
        if (size > MAX_FILE_BYTES) {
            List<String> tail = this.files.readLastLines(source, MAX_LOG_LINES);
            contents = "[truncated: the original is " + size + " bytes; the last "
                    + tail.size() + " lines follow]\n" + String.join("\n", tail) + "\n";
            truncated = true;
        } else {
            contents = this.files.readString(source);
        }

        this.addEntry(tar, entryName, contents);
        manifest.collected(entryName, truncated ? size + " bytes, TRUNCATED to the tail"
                : size + " bytes");
    }

    private void addEntry(TarArchiveOutputStream tar, String name, String contents)
            throws IOException {
        byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);
        entry.setModTime(this.clock.millis());
        tar.putArchiveEntry(entry);
        tar.write(bytes);
        tar.closeArchiveEntry();
    }

    /** Writing the bundle failed -- itself a thing worth explaining rather than stack-tracing. */
    public static class BundleFailedException extends RuntimeException {
        private final String remedy;

        public BundleFailedException(String message, String remedy, Throwable cause) {
            super(message, cause);
            this.remedy = remedy;
        }

        public String remedy() {
            return this.remedy;
        }
    }
}
