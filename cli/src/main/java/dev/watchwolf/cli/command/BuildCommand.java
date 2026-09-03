package dev.watchwolf.cli.command;

import dev.watchwolf.cli.ExitCodes;
import dev.watchwolf.cli.inventory.ServerJarInventory;
import dev.watchwolf.cli.model.BuildPlan;
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
import dev.watchwolf.cli.tui.menu.MenuConfigScreen;
import dev.watchwolf.cli.tui.menu.MenuModel;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

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

    @Override
    public Integer call() throws Exception {
        try (CliContext cli = new CliContext(this.options)) {
            Optional<BuildPlan> plan = this.resolvePlan(cli);
            if (plan.isEmpty()) {
                System.out.println("[i] Cancelled. Nothing was changed.");
                return ExitCodes.OK;
            }

            if (this.printPlan) {
                this.describe(plan.get());
                return ExitCodes.OK;
            }

            StepContext context = cli.stepContext(plan.get());
            var graph = StepCatalog.buildGraph(context);

            if (this.dryRun) {
                System.out.println("Steps, in order, with the check each one must pass:");
                for (var step : graph.ordered()) {
                    System.out.println("  " + step.id());
                    System.out.println("      " + step.title());
                    System.out.println("      verify: " + step.verification().describe());
                }
                return ExitCodes.OK;
            }

            StepRunner runner = StepRunner.reporting(PlainStepReporter.toStdout());
            if (this.failFast) runner = runner.failingFast();
            if (this.verifyOnly) runner = runner.verifyingOnly();

            List<StepResult> results = runner.run(graph, context);
            boolean failed = results.stream().anyMatch(result -> result.outcome().isFailure());

            int hostAction = HostActionFlush.flush(cli,
                    failed ? ExitCodes.ERROR : ExitCodes.OK);
            if (failed) return ExitCodes.ERROR;
            return hostAction;
        }
    }

    /** The menu and the flags converge on one plan. */
    private Optional<BuildPlan> resolvePlan(CliContext cli) throws IOException {
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

        MenuModel menu = new MenuModel(fromFlags, cli.layout().base().toString());
        menu.withInstalled(this.installedVersions(cli, "Spigot"),
                this.installedVersions(cli, "Paper"));

        try (MenuConfigScreen screen =
                     new MenuConfigScreen(menu, new BackgroundVersionFetcher(cli.http()))) {
            return screen.run();
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
        System.out.println("branch                 " + plan.branch());
        System.out.println("parallel builders      " + plan.parallelBuilders());
        System.out.println("clone ServersManager   " + plan.cloneServersManager());
        System.out.println("clone ClientsManager   " + plan.cloneClientsManager());
        System.out.println("clone Tester           " + plan.cloneTester());
        System.out.println("pull JDK images        " + plan.pullJdkImages());
        System.out.println("spigot versions        " + plan.spigotVersions());
        System.out.println("paper versions         " + plan.paperVersions());
        System.out.println("usual plugins          " + (plan.usualPluginsSelectionResolved()
                ? plan.selectedUsualPlugins().size() + " selected"
                : "all (unresolved until the build runs)"));
        System.out.println("WatchWolf-Server       " + plan.downloadWatchWolfServer());
        System.out.println("build images           " + plan.buildServersManagerImage());
        System.out.println("register at startup    " + plan.registerStartup());
        System.out.println("self-test              " + plan.runSelfTest()
                + (plan.runSelfTest()
                   ? " [" + TesterSuiteCatalog.testPatternFor(plan.selfTestSuites()) + "]" : ""));
    }
}
