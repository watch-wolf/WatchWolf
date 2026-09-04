package dev.watchwolf.cli.step.install;

import dev.watchwolf.cli.bundle.BundleWriter;
import dev.watchwolf.cli.doctor.CheckResult;
import dev.watchwolf.cli.doctor.CompatibilityMatrixSource;
import dev.watchwolf.cli.doctor.DoctorReport;
import dev.watchwolf.cli.doctor.Tier1Suite;
import dev.watchwolf.cli.doctor.Tier2Runner;
import dev.watchwolf.cli.net.HostInterfaces;
import dev.watchwolf.cli.net.PortProbe;
import dev.watchwolf.cli.step.*;

import java.nio.file.Path;
import java.util.Set;

/**
 * Ends an install by proving the environment works.
 *
 * <p>Tier 1 always; tier 2 (the real Tester suites) when a Tester checkout exists and suites were
 * selected. On failure it writes a diagnostics bundle and prints its path, so the next thing the
 * user does is attach one file rather than paste 900 lines of stack trace.
 */
public final class SelfTestStep implements Step {
    public static final StepId ID = StepId.of("self-test");

    @Override public StepId id()    { return ID; }
    @Override public String title() { return "Run the self-diagnosis"; }

    @Override
    public boolean isApplicable(StepContext context) {
        return context.plan().runSelfTest();
    }

    @Override
    public String skipReason(StepContext context) {
        if (!context.plan().cloneTester()) {
            return "WatchWolf-Tester was not installed, so the end-to-end test cannot run";
        }
        if (context.plan().selfTestSuites().isEmpty()) return "no self-test suites selected";
        return "not selected";
    }

    @Override
    public void perform(StepContext context) throws StepFailedException {
        DoctorReport tier1 = new Tier1Suite(new CompatibilityMatrixSource.AbsentMatrixSource(),
                new PortProbe(), new HostInterfaces()).run(context, false);
        tier1.printTo(System.out);

        if (tier1.count(CheckResult.Severity.FAIL) > 0) {
            throw new StepFailedException("the self-diagnosis",
                    tier1.count(CheckResult.Severity.FAIL) + " static check(s) failed",
                    "Fix the failures listed above, then run 'watchwolf doctor'. The end-to-end "
                            + "test was not attempted, because it would fail for the same reason.");
        }

        Tier2Runner.Outcome outcome =
                new Tier2Runner().run(context, context.plan().selfTestSuites());
        if (!outcome.passed()) {
            Path bundle = this.writeBundle(context);
            throw new StepFailedException("the end-to-end self-diagnosis",
                    outcome.detail(),
                    outcome.remedy() + (bundle == null ? "" : " Bundle: " + bundle));
        }
    }

    private Path writeBundle(StepContext context) {
        try {
            return new BundleWriter(context.docker(), context.files(), context.layout(),
                    new HostInterfaces(), context.clock())
                    .write(context.layout().exportedLogsDir().resolve("watchwolf-selftest-failure.tar.gz"),
                            BundleWriter.Selection.everything(), context.progress());
        } catch (RuntimeException ex) {
            context.progress().warn("Could not write a diagnostics bundle: " + ex.getMessage());
            return null;
        }
    }

    @Override
    public Verification verification() {
        // Nothing on disk proves a test run happened, and re-running it as a "verification" would
        // double a multi-minute step. perform() already fails loudly on its own.
        return Verification.nothingToVerify(
                "the self-diagnosis reports its own result; there is no artefact to check");
    }
}
