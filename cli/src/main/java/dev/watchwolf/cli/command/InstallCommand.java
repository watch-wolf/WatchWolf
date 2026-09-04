package dev.watchwolf.cli.command;

import dev.watchwolf.cli.ExitCodes;
import dev.watchwolf.cli.model.BuildPlan;
import dev.watchwolf.cli.model.TesterSuiteCatalog;
import dev.watchwolf.cli.step.PlainStepReporter;
import dev.watchwolf.cli.step.StepContext;
import dev.watchwolf.cli.step.StepResult;
import dev.watchwolf.cli.step.StepRunner;
import dev.watchwolf.cli.step.build.StepCatalog;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Registers WatchWolf with the host, and proves the result works.
 *
 * <p>Both registrations are host state an unprivileged container cannot write, so they are rendered
 * into a script the launcher shows and asks about. The command then ends with the self-diagnosis --
 * the point being that an install either works on this machine or says why, here, rather than three
 * weeks later inside somebody's JUnit run.
 */
@Command(name = "install",
        header = "Register WatchWolf on this machine and verify it works.",
        description = {
                "Installs the launcher to /usr/local/bin and (unless --disable-startup) registers",
                "a boot service. Ends with the self-diagnosis unless --skip-self-test."
        })
public class InstallCommand implements Callable<Integer> {

    @Mixin
    GlobalOptions options = new GlobalOptions();

    @Option(names = "--disable-startup", description = "Do not start WatchWolf at boot.")
    boolean disableStartup;

    @Option(names = "--skip-self-test", description = "Do not run the self-diagnosis.")
    boolean skipSelfTest;

    @Option(names = "--self-test-suites", split = ",",
            description = "Which Tester suites to run. Default: ${DEFAULT-VALUE}")
    List<String> selfTestSuites = List.copyOf(TesterSuiteCatalog.defaultSelection());

    @Override
    public Integer call() {
        try (CliContext cli = new CliContext(this.options, "install")) {
            BuildPlan plan = BuildPlan.builder()
                    .branch(this.options.resolvedBranch())
                    .registerLauncher(true)
                    .registerStartup(!this.disableStartup)
                    .runSelfTest(!this.skipSelfTest)
                    .selfTestSuites(new LinkedHashSet<>(this.selfTestSuites))
                    .build();

            StepContext context = cli.stepContext(plan);
            List<StepResult> results = StepRunner.reporting(PlainStepReporter.toStdout())
                    .run(StepCatalog.installGraph(context), context);

            boolean failed = results.stream().anyMatch(result -> result.outcome().isFailure());
            int code = HostActionFlush.flush(cli, failed ? ExitCodes.ERROR : ExitCodes.OK);

            if (!failed && code == ExitCodes.OK) {
                System.out.println("[i] WatchWolf installed.");
            }
            return failed ? ExitCodes.ERROR : code;
        }
    }
}
