package dev.watchwolf.cli.log;

import dev.watchwolf.cli.io.FileGateway;
import dev.watchwolf.cli.layout.InstallLayout;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * What the CLI did, written down as it happens -- one file per run under
 * {@code <base>/.watchwolf/run-logs/}.
 *
 * <p>Until this existed, the installer's own account of itself lived only in the terminal it ran
 * in. Three cases made that untenable:
 *
 * <ul>
 *   <li>The <b>drawn install</b> prints nothing at all while it runs -- it cannot, a Lanterna
 *       screen owns the terminal -- so an hour of steps left no trace anywhere.</li>
 *   <li>An install <b>sent to the background</b> finishes in a detached container; its output lives
 *       in {@code docker logs} only until that container is removed, and nobody was watching.</li>
 *   <li>A <b>diagnostics bundle</b> could say what the ServersManager and the containers did, but
 *       nothing about the run that built them -- which is exactly what "it installed wrong" needs.
 *       {@code BundleWriter} now collects these.</li>
 * </ul>
 *
 * <p>Records state changes, not heartbeats: every operation that begins, ends, warns or reports a
 * detail, and every step outcome with its remedy -- but not the per-second progress updates, which
 * would bury all of that under thousands of "12m elapsed" lines. Detail is written whether or not
 * {@code --verbose} was passed: the screen and the file are allowed to disagree, and the file is
 * the one somebody reads afterwards.
 *
 * <p><b>Never fails a command.</b> An unwritable install base is a reason to lose the log, not to
 * lose the install, so every failure here degrades to {@link #disabled()}.
 */
public final class RunLog implements AutoCloseable {
    /** Enough to cover "it worked last week", small enough to never need explaining. */
    private static final int KEEP_RUNS = 20;

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LINE_STAMP =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    private final FileGateway files;
    private final Clock clock;
    private final Path path;
    private final Instant startedAt;

    private boolean broken;

    private RunLog(FileGateway files, Clock clock, Path path, Instant startedAt) {
        this.files = files;
        this.clock = clock;
        this.path = path;
        this.startedAt = startedAt;
    }

    /**
     * Opens the log for one run of {@code command}. Returns {@link #disabled()} rather than
     * throwing when the directory cannot be written -- see the class Javadoc.
     */
    public static RunLog open(FileGateway files, InstallLayout layout, Clock clock,
                              String command, List<String> header) {
        Instant startedAt = clock.instant();
        Path directory = layout.cliLogsDir();
        Path path = directory.resolve(FILE_STAMP.format(startedAt) + "-" + command + ".log");

        RunLog log = new RunLog(files, clock, path, startedAt);
        try {
            files.createDirectories(directory);
        } catch (IOException ex) {
            return disabled();
        }

        log.prune(directory);

        StringBuilder text = new StringBuilder();
        text.append("watchwolf ").append(command).append('\n');
        text.append("started      ").append(startedAt).append('\n');
        text.append("install base ").append(layout.base()).append('\n');
        for (String line : header) text.append(line).append('\n');
        text.append("-".repeat(78)).append('\n');
        log.write(text.toString());
        return log;
    }

    /** A log that goes nowhere, for tests and for a base directory we cannot write. */
    public static RunLog disabled() {
        RunLog log = new RunLog(null, null, null, null);
        log.broken = true;
        return log;
    }

    public boolean isEnabled() {
        return !this.broken;
    }

    /** Where it is being written, for a command that wants to tell the user. */
    public Path path() {
        return this.path;
    }

    /** One timestamped line. Anything already written stays written if this run is killed. */
    public void line(String text) {
        if (this.broken) return;
        this.write(LINE_STAMP.format(this.clock.instant()) + "  " + text + "\n");
    }

    /**
     * Writes a whole captured output next to this run's log, as
     * {@code <same timestamp>-<name>.log}, and leaves a pointer to it in the log itself.
     *
     * <p>For output that is far too long to inline but is the entire diagnosis when something
     * fails -- BuildTools' console for a Spigot version that did not compile, say. Sharing the
     * run's timestamp is the point: in a bundle holding several runs, an attachment is
     * unambiguously part of one of them.
     *
     * @return where it was written, or empty when logging is disabled or the write failed
     */
    public Optional<Path> attachment(String name, List<String> lines) {
        if (this.broken) return Optional.empty();

        Path attachment = this.path.resolveSibling(
                FILE_STAMP.format(this.startedAt) + "-" + name + ".log");
        try {
            this.files.writeString(attachment, String.join("\n", lines) + "\n");
        } catch (IOException | RuntimeException ex) {
            this.line("[w] could not write " + attachment + ": " + ex.getMessage());
            return Optional.empty();
        }
        this.line("[i] " + lines.size() + " line(s) of " + name + " output written to "
                + attachment);
        return Optional.of(attachment);
    }

    /** A titled block -- the resolved plan, say -- set apart from the running commentary. */
    public void section(String title, List<String> lines) {
        if (this.broken) return;
        StringBuilder text = new StringBuilder("\n").append(title).append('\n');
        for (String line : lines) text.append("    ").append(line).append('\n');
        text.append('\n');
        this.write(text.toString());
    }

    @Override
    public void close() {
        if (this.broken) return;
        Duration elapsed = Duration.between(this.startedAt, this.clock.instant());
        this.write("-".repeat(78) + "\nfinished     " + this.clock.instant()
                + "  (" + human(elapsed) + ")\n");
    }

    private void write(String text) {
        try {
            this.files.appendString(this.path, text);
        } catch (IOException | RuntimeException ex) {
            // one failed append means the rest will fail too; stop trying rather than printing a
            // warning per line underneath whatever the command is doing
            this.broken = true;
        }
    }

    /** Keeps the newest {@link #KEEP_RUNS}; a machine that installs often should not grow a heap. */
    private void prune(Path directory) {
        List<Path> logs = new ArrayList<>();
        for (Path entry : this.files.list(directory)) {
            if (entry.getFileName().toString().endsWith(".log")) logs.add(entry);
        }
        if (logs.size() < KEEP_RUNS) return;

        logs.sort(Comparator.comparing(this.files::lastModified).reversed());
        for (Path old : logs.subList(KEEP_RUNS - 1, logs.size())) {
            try {
                this.files.delete(old);
            } catch (IOException | RuntimeException ex) {
                // a log we cannot delete is not worth failing over; the next run tries again
            }
        }
    }

    private static String human(Duration elapsed) {
        long seconds = Math.max(0, elapsed.toSeconds());
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m" + (seconds % 60) + "s";
        return (seconds / 3600) + "h" + ((seconds % 3600) / 60) + "m";
    }
}
