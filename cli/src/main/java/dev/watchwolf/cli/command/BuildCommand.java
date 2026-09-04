package dev.watchwolf.cli.command;

import dev.watchwolf.cli.ExitCodes;
import dev.watchwolf.cli.inventory.ServerJarInventory;
import dev.watchwolf.cli.model.BuildPlan;
import dev.watchwolf.cli.model.BuildPlanFile;
import dev.watchwolf.cli.model.InstallRunRecord;
import dev.watchwolf.cli.model.McVersion;
import dev.watchwolf.cli.model.ServerTypeVersion;
import dev.watchwolf.cli.model.TesterSuiteCatalog;
import dev.watchwolf.cli.progress.ProgressSink;
import dev.watchwolf.cli.remote.PaperApiClient;
import dev.watchwolf.cli.remote.SpigotHubClient;
import dev.watchwolf.cli.step.PlainStepReporter;
import dev.watchwolf.cli.step.StepContext;
import dev.watchwolf.cli.step.StepResult;
import dev.watchwolf.cli.step.StepRunner;
import dev.watchwolf.cli.step.build.StepCatalog;
import dev.watchwolf.cli.tui.TerminalCapability;
import dev.watchwolf.cli.tui.install.AcknowledgeScreen;
import dev.watchwolf.cli.tui.install.InstallProgressModel;
import dev.watchwolf.cli.tui.install.InstallProgressScreen;
import dev.watchwolf.cli.tui.install.TuiProgressSink;
import dev.watchwolf.cli.tui.install.TuiStepReporter;
import dev.watchwolf.cli.tui.menu.MenuConfigScreen;
import dev.watchwolf.cli.tui.menu.MenuModel;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Installs and builds the environment.
 *
 * <p>On a terminal with no explicit selection flags it opens the menuconfig screen; otherwise it
 * runs straight from the flags. Both paths produce the same {@link BuildPlan}, so nothing
 * downstream knows or cares which was used -- and {@code --print-plan} can show exactly what a TUI
 * session would have done.
 *
 * <p>Replaces {@code WatchWolfSetup.sh --build}, minus its two foot-guns: nothing is deleted
 * (every step is idempotent and verified instead), and the JDK image list is derived from the
 * ServersManager's own logic rather than hardcoded and stale.
 */
@Command(name = "build",
        header = "Install and build a WatchWolf environment.",
        description = {
                "With no selection flags on a terminal, opens a checkbox menu. Every step is",
                "verified after it runs, and nothing is ever deleted: a second run updates in",
                "place."
        })
public class BuildCommand implements Callable<Integer> {

    @Mixin
    GlobalOptions options = new GlobalOptions();

    @Option(names = "--threads", description = "Parallel Spigot build containers.")
    Integer threads;

    @Option(names = "--skip-spigot-build", description = "Do not build any Spigot version.")
    boolean skipSpigot;

    @Option(names = "--skip-paper", description = "Do not download any Paper version.")
    boolean skipPaper;

    @Option(names = "--spigot", split = ",",
            description = "Spigot versions to build, or 'all', or 'newest:<n>'.")
    List<String> spigot;

    @Option(names = "--paper", split = ",",
            description = "Paper versions to download, or 'all', or 'newest:<n>'.")
    List<String> paper;

    @Option(names = "--skip-tester", description = "Do not clone WatchWolf-Tester.")
    boolean skipTester;

    @Option(names = "--skip-self-test", description = "Do not run the self-diagnosis afterwards.")
    boolean skipSelfTest;

    @Option(names = "--self-test-suites", split = ",",
            description = "Which Tester suites the self-diagnosis runs.")
    List<String> selfTestSuites;

    @Option(names = "--fail-fast", description = "Stop at the first failing step.")
    boolean failFast;

    @Option(names = "--verify-only",
            description = "Run every verification and no work -- a second doctor.")
    boolean verifyOnly;

    @Option(names = "--print-plan", description = "Show the resolved plan and exit.")
    boolean printPlan;

    @Option(names = "--dry-run", description = "List the steps and their checks, then exit.")
    boolean dryRun;

    /**
     * The other half of "send it to the background". The CLI runs attached to your terminal and
     * cannot detach itself, so it exits {@link ExitCodes#BACKGROUND_REQUESTED} and the launcher
     * starts a detached container that re-enters here -- replaying the saved plan with no menu and
     * plain output, and leaving the result behind for the next run to show. Hidden because it is
     * the launcher's business, not a user's.
     */
    @Option(names = "--resume-background", hidden = true)
    boolean resumeBackground;

    /** Set by {@link #resolvePlan}: only a menu session gets the drawn install. */
    private boolean usedMenu;

    @Override
    public Integer call() throws Exception {
        try (CliContext cli = new CliContext(this.options, "build")) {
            Optional<BuildPlan> plan = this.resolvePlan(cli);
            if (plan.isEmpty()) {
                // a detached run with no plan to replay has failed at its one job, and nobody is
                // watching it say so -- so it exits non-zero and leaves the reason behind
                if (this.resumeBackground) {
                    this.recordProblemForTheNextRun(cli, "the saved plan could not be read");
                    return ExitCodes.ERROR;
                }
                System.out.println("[i] Cancelled. Nothing was changed.");
                return ExitCodes.OK;
            }

            if (this.printPlan) {
                this.describe(plan.get());
                return ExitCodes.OK;
            }

            // what was actually asked for, at the top of the log -- the first question anybody
            // reading it afterwards has
            cli.runLog().section("plan", planLines(plan.get()));

            if (this.dryRun) {
                StepContext context = cli.stepContext(plan.get());
                System.out.println("Steps, in order, with the check each one must pass:");
                for (var step : StepCatalog.buildGraph(context).ordered()) {
                    System.out.println("  " + step.id());
                    System.out.println("      " + step.title());
                    System.out.println("      verify: " + step.verification().describe());
                }
                return ExitCodes.OK;
            }

            // came from the menu, so the install is drawn too: switching back to scrolling output
            // for the next hour hides the one thing worth watching, which Spigot jar is still going
            if (this.usedMenu) return this.runDrawn(cli, plan.get());
            return this.runPrinted(cli, plan.get());
        }
    }

    /** The original path: one line per step, straight to the terminal. */
    private int runPrinted(CliContext cli, BuildPlan plan) {
        StepContext context = cli.stepContext(plan);
        List<StepResult> results =
                this.runner(cli, PlainStepReporter.toStdout())
                        .run(StepCatalog.buildGraph(context), context);

        if (this.resumeBackground) this.recordForTheNextRun(cli, results);

        boolean failed = results.stream().anyMatch(result -> result.outcome().isFailure());
        int hostAction = HostActionFlush.flush(cli, failed ? ExitCodes.ERROR : ExitCodes.OK);
        if (failed) return ExitCodes.ERROR;
        return hostAction;
    }

    /**
     * The install under the same UI the menu used: the runner works on its own thread and the
     * screen only paints what it publishes, so keys stay responsive through an hour-long build.
     */
    private int runDrawn(CliContext cli, BuildPlan plan) throws IOException {
        InstallProgressModel model = new InstallProgressModel();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<List<StepResult>> results = new AtomicReference<>(List.of());

        StepContext context = cli.stepContext(plan,
                cli.logging(new TuiProgressSink(model)), cancelled::get);
        var graph = StepCatalog.buildGraph(context);
        StepRunner runner = this.runner(cli, new TuiStepReporter(model));

        Thread worker = new Thread(() -> {
            List<StepResult> ran = List.of();
            try {
                ran = runner.run(graph, context);
            } catch (RuntimeException ex) {
                // the runner turns step failures into results; reaching here is a bug in it, and
                // the screen must still be told the run is over or it would spin forever
                model.warn("the install stopped unexpectedly: " + ex);
            }
            results.set(ran);
            model.runFinished(ran, cancelled.get()
                    ? InstallProgressModel.Ending.ABORTED
                    : InstallProgressModel.Ending.COMPLETED);
        }, "watchwolf-install");
        worker.setDaemon(true);
        worker.start();

        InstallProgressModel.Ending ending = new InstallProgressScreen(model).run();

        if (ending != InstallProgressModel.Ending.COMPLETED) {
            cancelled.set(true);
            System.out.println(ending == InstallProgressModel.Ending.BACKGROUNDED
                    ? "[i] Handing the install over to a background container..."
                    : "[i] Stopping. Waiting for the step in flight to finish so nothing is left "
                            + "half-written...");
            try {
                worker.join();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        cli.runLog().line("[i] the install ended: " + ending.name().toLowerCase());

        return switch (ending) {
            case COMPLETED -> this.finishDrawnRun(cli, results.get());
            case ABORTED -> {
                System.out.println("[i] Aborted. Nothing already done was undone -- re-run "
                        + "'watchwolf build' to carry on from here.");
                yield ExitCodes.OK;
            }
            case BACKGROUNDED -> this.handOverToTheBackground(cli, plan);
        };
    }

    /** The screen is gone by now, so the failures and their remedies go back into scrollback. */
    private int finishDrawnRun(CliContext cli, List<StepResult> results) {
        PlainStepReporter.toStdout().runFinished(results);
        boolean failed = results.stream().anyMatch(result -> result.outcome().isFailure());
        int hostAction = HostActionFlush.flush(cli, failed ? ExitCodes.ERROR : ExitCodes.OK);
        if (failed) return ExitCodes.ERROR;
        return hostAction;
    }

    /**
     * Writes the plan down and asks the launcher to start it detached. This process cannot detach
     * itself -- it is the foreground of a {@code docker run -it} -- so the handover is an exit code
     * plus a file, and the launcher does the rest.
     */
    private int handOverToTheBackground(CliContext cli, BuildPlan plan) {
        try {
            cli.files().createDirectories(cli.layout().stateDir());
            cli.files().writeString(cli.layout().buildPlanFile(), BuildPlanFile.render(plan));
        } catch (IOException ex) {
            System.err.println("[e] Could not write " + cli.layout().buildPlanFile() + ": "
                    + ex.getMessage());
            System.err.println("[e] remedy: check the permissions on " + cli.layout().stateDir()
                    + ", then re-run 'watchwolf build'.");
            return ExitCodes.ERROR;
        }
        System.out.println("[i] The install carries on in the background. The next 'watchwolf "
                + "build' opens with how it ended.");
        return ExitCodes.BACKGROUND_REQUESTED;
    }

    /** The same note, for a background run that could not even start. */
    private void recordProblemForTheNextRun(CliContext cli, String problem) {
        this.write(cli, new InstallRunRecord("backgrounded", "install could not start",
                Instant.now(cli.clock()).toString(), List.of(problem)));
    }

    /** Leaves the result where the next run will find it -- see {@link InstallRunRecord}. */
    private void recordForTheNextRun(CliContext cli, List<StepResult> results) {
        List<String> failures = new ArrayList<>();
        for (StepResult result : results) {
            if (!result.outcome().isFailure()) continue;
            failures.add(result.id() + ": " + result.what() + ": " + result.why());
        }
        String summary = failures.isEmpty()
                ? "install successful"
                : "install failed: " + failures.size() + " step(s) of " + results.size();

        this.write(cli, new InstallRunRecord("backgrounded", summary,
                Instant.now(cli.clock()).toString(), failures));
    }

    private void write(CliContext cli, InstallRunRecord record) {
        try {
            cli.files().createDirectories(cli.layout().stateDir());
            cli.files().writeString(cli.layout().lastRunFile(), record.render());
        } catch (IOException ex) {
            // the run itself succeeded or failed on its own merits; failing to leave a note about
            // it is worth saying, but not worth changing the exit code over
            System.err.println("[w] Could not record the result in "
                    + cli.layout().lastRunFile() + ": " + ex.getMessage());
        }
    }

    private StepRunner runner(CliContext cli, dev.watchwolf.cli.step.StepReporter reporter) {
        StepRunner runner = StepRunner.reporting(cli.logging(reporter));
        if (this.failFast) runner = runner.failingFast();
        if (this.verifyOnly) runner = runner.verifyingOnly();
        return runner;
    }

    /** The menu and the flags converge on one plan. */
    private Optional<BuildPlan> resolvePlan(CliContext cli) throws IOException {
        if (this.resumeBackground) return this.planFromTheSavedFile(cli);

        BuildPlan fromFlags = this.planFromFlags(cli);

        boolean anySelectionFlag = this.spigot != null || this.paper != null
                || this.skipSpigot || this.skipPaper || this.skipTester || this.skipSelfTest
                || this.selfTestSuites != null || this.threads != null;

        if (anySelectionFlag || !this.options.canUseTui() || this.printPlan || this.dryRun
                || this.verifyOnly) {
            if (!anySelectionFlag && !TerminalCapability.available()
                    && !this.printPlan && !this.dryRun && !this.verifyOnly) {
                System.out.println("[i] No terminal available (" + TerminalCapability.whyUnavailable()
                        + "), so the menu cannot open. Using defaults and flags.");
            }
            return Optional.of(this.resolveVersionsForFlags(cli, fromFlags));
        }

        this.showAnyPendingResult(cli);

        MenuModel menu = new MenuModel(fromFlags, cli.layout().base().toString());
        menu.withInstalled(this.installedVersions(cli, "Spigot"),
                this.installedVersions(cli, "Paper"));

        try (MenuConfigScreen screen =
                     new MenuConfigScreen(menu, new BackgroundVersionFetcher(cli.http()))) {
            Optional<BuildPlan> chosen = screen.run();
            this.usedMenu = chosen.isPresent();
            return chosen;
        }
    }

    /**
     * A background run finished with nobody watching, so its result waits here and is shown before
     * the menu -- once, behind an {@code < OK >}, and then deleted.
     */
    private void showAnyPendingResult(CliContext cli) throws IOException {
        if (!cli.files().exists(cli.layout().lastRunFile())) return;

        InstallRunRecord record;
        try {
            record = InstallRunRecord.parse(cli.files().readString(cli.layout().lastRunFile()));
        } catch (IOException ex) {
            // an unreadable note is not worth blocking a build over
            return;
        }

        List<String> lines = new ArrayList<>();
        lines.add("The install you sent to the background has finished.");
        lines.add("");
        lines.add(record.summary());
        if (!record.finishedAt().isBlank()) lines.add("finished at " + record.finishedAt());
        if (!record.failures().isEmpty()) {
            lines.add("");
            record.failures().forEach(lines::add);
            lines.add("");
            lines.add("Re-running picks up from where it stopped; nothing was undone.");
        }

        new AcknowledgeScreen(record.summary(), lines, record.succeeded()).run();

        try {
            cli.files().delete(cli.layout().lastRunFile());
        } catch (IOException ex) {
            System.err.println("[w] Could not remove " + cli.layout().lastRunFile()
                    + "; it will be shown again next time.");
        }
    }

    /** What {@code --resume-background} replays: the plan the foreground session ticked. */
    private Optional<BuildPlan> planFromTheSavedFile(CliContext cli) {
        if (!cli.files().exists(cli.layout().buildPlanFile())) {
            System.err.println("[e] There is no saved plan at " + cli.layout().buildPlanFile()
                    + ", so there is nothing to carry on with.");
            System.err.println("[e] remedy: run 'watchwolf build' again and pick what to install.");
            return Optional.empty();
        }
        try {
            return Optional.of(
                    BuildPlanFile.parse(cli.files().readString(cli.layout().buildPlanFile())));
        } catch (IOException ex) {
            System.err.println("[e] Could not read the saved plan at "
                    + cli.layout().buildPlanFile() + ": " + ex.getMessage());
            System.err.println("[e] remedy: run 'watchwolf build' again and pick what to install.");
            return Optional.empty();
        }
    }

    private Set<McVersion> installedVersions(CliContext cli, String type) {
        Set<McVersion> versions = new LinkedHashSet<>();
        for (ServerTypeVersion entry :
                new ServerJarInventory(cli.files(), cli.layout()).installed()) {
            if (entry.type().equalsIgnoreCase(type)) versions.add(entry.version());
        }
        return versions;
    }

    private BuildPlan planFromFlags(CliContext cli) {
        BuildPlan.Builder builder = BuildPlan.builder()
                .branch(this.options.resolvedBranch())
                .buildSpigot(!this.skipSpigot)
                .buildPaper(!this.skipPaper)
                .cloneTester(!this.skipTester)
                .runSelfTest(!this.skipSelfTest);

        if (this.threads != null) builder.parallelBuilders(this.threads);
        if (this.selfTestSuites != null) {
            builder.selfTestSuites(new LinkedHashSet<>(this.selfTestSuites));
        }
        return builder.build();
    }

    /** {@code all}, {@code newest:3} and explicit lists all need the remote index. */
    private BuildPlan resolveVersionsForFlags(CliContext cli, BuildPlan plan) {
        BuildPlan.Builder builder = plan.toBuilder();

        if (plan.buildSpigot()) {
            builder.spigotVersions(this.resolveVersions(this.spigot,
                    () -> new SpigotHubClient(cli.http()).availableVersions(cli.progress())));
        }
        if (plan.buildPaper()) {
            builder.paperVersions(this.resolveVersions(this.paper,
                    () -> new PaperApiClient(cli.http()).availableVersions(cli.progress())));
        }
        return builder.build();
    }

    private List<McVersion> resolveVersions(List<String> requested,
                                            java.util.function.Supplier<List<McVersion>> remote) {
        if (requested == null || requested.isEmpty()) return List.of();

        if (requested.size() == 1) {
            String only = requested.get(0).strip();
            if (only.equalsIgnoreCase("all")) return remote.get();
            if (only.toLowerCase().startsWith("newest:")) {
                int count = Integer.parseInt(only.substring("newest:".length()));
                List<McVersion> available = remote.get();
                return available.subList(0, Math.min(count, available.size()));
            }
        }

        List<McVersion> explicit = new java.util.ArrayList<>();
        for (String text : requested) {
            McVersion version = McVersion.parseOrNull(text);
            if (version == null) {
                throw new IllegalArgumentException("'" + text + "' is not a Minecraft version. "
                        + "Use versions like 1.20.4, or 'all', or 'newest:3'.");
            }
            explicit.add(version);
        }
        return explicit;
    }

    private void describe(BuildPlan plan) {
        planLines(plan).forEach(System.out::println);
    }

    /** The resolved plan as text -- printed by {@code --print-plan}, and kept in the run log. */
    private static List<String> planLines(BuildPlan plan) {
        List<String> lines = new ArrayList<>();
        lines.add("branch                 " + plan.branch());
        lines.add("parallel builders      " + plan.parallelBuilders());
        lines.add("clone ServersManager   " + plan.cloneServersManager());
        lines.add("clone ClientsManager   " + plan.cloneClientsManager());
        lines.add("clone Tester           " + plan.cloneTester());
        lines.add("pull JDK images        " + plan.pullJdkImages());
        lines.add("spigot versions        " + plan.spigotVersions());
        lines.add("paper versions         " + plan.paperVersions());
        lines.add("usual plugins          " + (plan.usualPluginsSelectionResolved()
                ? plan.selectedUsualPlugins().size() + " selected"
                : "all (unresolved until the build runs)"));
        lines.add("WatchWolf-Server       " + plan.downloadWatchWolfServer());
        lines.add("build images           " + plan.buildServersManagerImage());
        lines.add("register at startup    " + plan.registerStartup());
        lines.add("self-test              " + plan.runSelfTest()
                + (plan.runSelfTest()
                   ? " [" + TesterSuiteCatalog.testPatternFor(plan.selfTestSuites()) + "]" : ""));
        return lines;
    }
}
