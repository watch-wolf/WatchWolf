package dev.watchwolf.cli.step.build;

import dev.watchwolf.cli.step.Step;
import dev.watchwolf.cli.step.StepContext;
import dev.watchwolf.cli.step.StepGraph;
import dev.watchwolf.cli.step.install.RegisterLauncherStep;
import dev.watchwolf.cli.step.install.RegisterStartupStep;
import dev.watchwolf.cli.step.install.SelfTestStep;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Assembles the step graphs the commands run. */
public final class StepCatalog {

    private StepCatalog() {
    }

    /** Everything {@code build} does. */
    public static StepGraph buildGraph(StepContext context) {
        List<Step> steps = new ArrayList<>();

        steps.add(new PreflightDockerStep());
        steps.add(new ResolveInstallBaseStep());

        steps.add(new CloneStep(CloneStepIds.SERVERS_MANAGER,
                "Clone WatchWolf-ServersManager", Repositories.SERVERS_MANAGER,
                context.layout().serversManagerRepo(),
                ctx -> ctx.plan().cloneServersManager()));

        steps.add(new CloneStep(CloneStepIds.CLIENTS_MANAGER,
                "Clone WatchWolf-Client (the ClientsManager)", Repositories.CLIENTS_MANAGER,
                context.layout().clientsManagerRepo(),
                ctx -> ctx.plan().cloneClientsManager()));

        // the Tester carries the self-test suites, so its clone also verifies the catalog
        steps.add(new CloneStep(CloneStepIds.TESTER,
                "Clone WatchWolf-Tester (powers the self-diagnosis)", Repositories.TESTER,
                context.layout().testerRepo(),
                ctx -> ctx.plan().cloneTester(),
                new TesterSuitesResolveVerification()));

        steps.add(new CreateRuntimeDirsStep());
        steps.add(new PullJdkImagesStep());
        steps.add(new BuildSpigotJarsStep());
        steps.add(new DownloadPaperJarsStep());
        steps.add(new DownloadUsualPluginsStep());
        steps.add(new DownloadWatchWolfServerStep());
        steps.add(new BuildServersManagerImageStep());
        steps.add(new BuildClientsManagerImageStep());

        return StepGraph.of(steps);
    }

    /** {@code install}: the host registrations, then prove the result works. */
    public static StepGraph installGraph(StepContext context) {
        return StepGraph.of(List.of(
                new PreflightDockerStep(),
                new RegisterLauncherStep(),
                new RegisterStartupStep(),
                new SelfTestStep()));
    }

    /** Predicate helper, kept so the graph above reads as a list of decisions. */
    static Predicate<StepContext> always() {
        return context -> true;
    }
}
