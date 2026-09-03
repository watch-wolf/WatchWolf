package dev.watchwolf.cli.command;

import dev.watchwolf.cli.ExitCodes;
import dev.watchwolf.cli.bundle.BundleWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Exports one {@code tar.gz} answering a bug report.
 *
 * <p>The point is that a report becomes one attachment instead of 900 pasted lines -- and that the
 * bundle says what it could <em>not</em> collect, so a gap is explained rather than misleading.
 */
@Command(name = "logs",
        header = "Export a diagnostics bundle.",
        description = {
                "Collects component versions, the container inventory with published ports, host",
                "network facts, container output, and the ServersManager's per-session logs.",
                "Jars are inventoried by name and size, never copied."
        })
public class LogsCommand implements Callable<Integer> {

    @Mixin
    GlobalOptions options = new GlobalOptions();

    @Option(names = "--session", split = ",",
            description = "Only these session ids (the <millis> in logs/<millis>/).")
    List<String> sessions = List.of();

    @Option(names = "--last", description = "Only the newest N sessions.")
    int last = -1;

    @Option(names = "--since", description = "Only sessions newer than this (e.g. 2h, 30m).")
    String since;

    @Option(names = "--out",
            description = "Where to write. Default: <install base>/logs/watchwolf-logs-<time>.tar.gz")
    String out;

    @Override
    public Integer call() {
        try (CliContext cli = new CliContext(this.options)) {
            Path destination = this.destination(cli.layout().exportedLogsDir());

            BundleWriter.Selection selection = this.selection();
            BundleWriter writer = new BundleWriter(cli.docker(), cli.files(), cli.layout(),
                    cli.interfaces(), cli.clock());

            Path written = writer.write(destination, selection, cli.progress());

            System.out.println();
            System.out.println("[i] Wrote " + written);
            System.out.println("[i] Attach this to your report at "
                    + "https://github.com/watch-wolf/WatchWolf/issues");
            return ExitCodes.OK;

        } catch (BundleWriter.BundleFailedException ex) {
            System.err.println("[e] " + ex.getMessage());
            System.err.println("[e] " + ex.remedy());
            return ExitCodes.ERROR;
        }
    }

    private BundleWriter.Selection selection() {
        if (!this.sessions.isEmpty()) return BundleWriter.Selection.sessions(this.sessions);
        if (this.since != null) return BundleWriter.Selection.since(parseDuration(this.since));
        if (this.last > 0) return BundleWriter.Selection.last(this.last);
        return BundleWriter.Selection.everything();
    }

    /**
     * {@code --out} always wins. Otherwise {@code <install base>/logs/} -- one predictable place
     * regardless of which directory the command happened to be run from, and the same place
     * {@code doctor}'s failure bundle and the dashboard's {@code e} key already write to.
     */
    private Path destination(Path exportedLogsDir) {
        if (this.out != null) return Paths.get(this.out).toAbsolutePath();
        String stamp = String.valueOf(System.currentTimeMillis());
        return exportedLogsDir.resolve("watchwolf-logs-" + stamp + ".tar.gz");
    }

    /** Accepts {@code 90s}, {@code 30m}, {@code 2h}, {@code 3d}. */
    static Duration parseDuration(String text) {
        String trimmed = text.strip().toLowerCase();
        long amount;
        try {
            amount = Long.parseLong(trimmed.substring(0, trimmed.length() - 1));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(
                    "Could not read '" + text + "' as a duration. Use 90s, 30m, 2h or 3d.");
        }
        return switch (trimmed.charAt(trimmed.length() - 1)) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException(
                    "Could not read '" + text + "' as a duration. Use 90s, 30m, 2h or 3d.");
        };
    }
}
