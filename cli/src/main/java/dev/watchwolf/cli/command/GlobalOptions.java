package dev.watchwolf.cli.command;

import dev.watchwolf.cli.layout.InstallLayout;
import dev.watchwolf.cli.layout.RuntimeFlavor;
import dev.watchwolf.cli.progress.PlainProgressSink;
import dev.watchwolf.cli.progress.ProgressSink;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The options every subcommand accepts. Mixed in rather than repeated.
 */
public class GlobalOptions {
    @Option(names = "--path",
            description = "Install base directory. Default: ${DEFAULT-VALUE}")
    public String path = defaultBase();

    @Option(names = "--branch",
            description = "Branch to clone. Default: dev. 'master' is not selectable in the "
                    + "menu yet, but works here as an explicit override.")
    public String branch;

    @Option(names = "--verbose", description = "Print per-operation detail.")
    public boolean verbose;

    @Option(names = "--json", description = "Machine-readable output where supported.")
    public boolean json;

    @Option(names = "--no-tui",
            description = "Never open a full-screen interface; use flags and plain output.")
    public boolean noTui;

    private static String defaultBase() {
        String fromLauncher = System.getenv("WW_BASE");
        if (fromLauncher != null && !fromLauncher.isBlank()) return fromLauncher;
        return Paths.get(System.getProperty("user.home", "."), "WatchWolf").toString();
    }

    public Path basePath() {
        return Paths.get(this.path).toAbsolutePath();
    }

    public String resolvedBranch() {
        if (this.branch != null && !this.branch.isBlank()) return this.branch;
        // 'dev' for now: every repo integrates there first, and 'master' is not yet in a state
        // this CLI can rely on. --branch stays as the explicit escape hatch.
        return "dev";
    }

    /**
     * Picks the runtime flavour by looking for the directory. {@code ci/release} is what the
     * installer creates; {@code ci/debug} exists in a developer's checkout that compiled locally.
     */
    public InstallLayout layout() {
        Path base = this.basePath();
        InstallLayout release = new InstallLayout(base, RuntimeFlavor.RELEASE);
        if (Files.isDirectory(release.serversManagerRuntime())) return release;

        InstallLayout debug = new InstallLayout(base, RuntimeFlavor.DEBUG);
        if (Files.isDirectory(debug.serversManagerRuntime())) return debug;

        return release;   // not installed yet; release is what `build` will create
    }

    public ProgressSink progress() {
        return PlainProgressSink.toStdout(this.verbose);
    }

    /** False inside a pipe, a CI job, or with --no-tui: the TUIs need a real terminal. */
    public boolean canUseTui() {
        if (this.noTui || this.json) return false;
        String term = System.getenv("TERM");
        if (term == null || term.isBlank() || term.equals("dumb")) return false;
        return System.console() != null;
    }
}
