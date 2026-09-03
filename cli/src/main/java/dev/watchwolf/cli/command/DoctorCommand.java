package dev.watchwolf.cli.command;

import dev.watchwolf.cli.ExitCodes;
import dev.watchwolf.cli.bundle.BundleWriter;
import dev.watchwolf.cli.doctor.CompatibilityMatrixSource;
import dev.watchwolf.cli.doctor.DoctorReport;
import dev.watchwolf.cli.doctor.Tier1Suite;
import dev.watchwolf.cli.doctor.Tier2Runner;
import dev.watchwolf.cli.model.BuildPlan;
import dev.watchwolf.cli.model.TesterSuiteCatalog;
import dev.watchwolf.cli.step.StepContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Diagnoses the environment: fast static checks, then the real Tester suites.
 *
 * <p>Exits non-zero only on a genuine failure. A check that could not run reports {@code SKIP} and
 * still exits 0, because failing an install over a check that never ran is how you teach people to
 * pass {@code --skip-self-test} forever. {@code --strict} promotes warnings and skips.
 */
@Command(name = "doctor",
        header = "Check the environment, and optionally run the real test suites.",
        description = {
                "Tier 1 is static and takes seconds. Tier 2 runs WatchWolf-Tester's own",
                "integration suites against real servers and takes minutes."
        })
public class DoctorCommand implements Callable<Integer> {

    @Mixin
    GlobalOptions options = new GlobalOptions();

    @Option(names = "--quick", description = "Tier 1 only: the fast static checks.")
    boolean quick;

    @Option(names = "--strict",
            description = "Treat warnings and skipped checks as failures.")
    boolean strict;

    @Option(names = "--suites", split = ",",
            description = "Which Tester suites to run. Default: ${DEFAULT-VALUE}")
    List<String> suites = List.copyOf(TesterSuiteCatalog.defaultSelection());

    @Option(names = "--all-suites", description = "Run every suite in the catalog. Slow.")
    boolean allSuites;

    @Option(names = "--bundle-on-failure",
            description = "Write a diagnostics bundle if anything fails.",
            defaultValue = "true", negatable = true)
    boolean bundleOnFailure;

    @Override
    public Integer call() {
        try (CliContext cli = new CliContext(this.options)) {
            StepContext context = cli.stepContext(BuildPlan.defaults());

            System.out.println("Tier 1 -- static checks");
            DoctorReport tier1 = new Tier1Suite(new CompatibilityMatrixSource.AbsentMatrixSource(),
                    cli.portProbe(), cli.interfaces()).run(context, this.strict);
            tier1.printTo(System.out);

            if (this.quick) {
                return this.finish(cli, tier1.healthy());
            }

            if (!tier1.healthy()) {
                System.out.println();
                System.out.println("[i] Not running the end-to-end suites: they would fail for the "
                        + "reasons above, and take minutes doing it.");
                return this.finish(cli, false);
            }

            System.out.println();
            System.out.println("Tier 2 -- end-to-end, against real servers");
            Set<String> chosen = this.allSuites
                    ? TesterSuiteCatalog.allClassNames()
                    : new LinkedHashSet<>(this.suites);

            Tier2Runner.Outcome outcome = new Tier2Runner().run(context, chosen);
            if (outcome.passed()) {
                System.out.println("  [ok  ] " + outcome.detail());
                return this.finish(cli, true);
            }

            System.out.println("  [FAIL] " + outcome.detail());
            System.out.println("         -> " + outcome.remedy());
            return this.finish(cli, false);
        }
    }

    private int finish(CliContext cli, boolean healthy) {
        if (healthy) {
            System.out.println();
            System.out.println("[i] The environment looks healthy.");
            return ExitCodes.OK;
        }

        if (this.bundleOnFailure) {
            try {
                Path bundle = new BundleWriter(cli.docker(), cli.files(), cli.layout(),
                        cli.interfaces(), cli.clock())
                        .write(cli.layout().exportedLogsDir().resolve("watchwolf-doctor-failure.tar.gz"),
                                BundleWriter.Selection.last(5), cli.progress());
                System.out.println("[i] Diagnostics bundle: " + bundle);
            } catch (RuntimeException ex) {
                System.err.println("[w] Could not write a diagnostics bundle: " + ex.getMessage());
            }
        }
        return ExitCodes.DOCTOR_FAILED;
    }
}
