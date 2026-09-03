package dev.watchwolf.cli.doctor;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The outcome of a doctor run.
 *
 * <p><b>Exit policy:</b> non-zero only on {@code FAIL}. {@code WARN} and {@code SKIP} exit 0,
 * because failing an install over a check that could not run is how you teach people to pass
 * {@code --skip-self-test} forever. {@code --strict} promotes both, and is the flag CI flips the
 * day the compatibility matrix exists.
 */
public final class DoctorReport {
    private final List<CheckResult> results = new ArrayList<>();
    private final boolean strict;

    public DoctorReport(boolean strict) {
        this.strict = strict;
    }

    public void add(CheckResult result) {
        this.results.add(result);
    }

    public List<CheckResult> results() {
        return List.copyOf(this.results);
    }

    public boolean healthy() {
        for (CheckResult result : this.results) {
            if (result.isFailure()) return false;
            if (this.strict && result.severity() != CheckResult.Severity.PASS) return false;
        }
        return true;
    }

    public long count(CheckResult.Severity severity) {
        return this.results.stream().filter(r -> r.severity() == severity).count();
    }

    public void printTo(PrintStream out) {
        for (CheckResult result : this.results) {
            out.printf("  [%s] %s%n", result.severity().label(), result.what());
            if (result.detail() != null && !result.detail().isBlank()) {
                out.println("         " + result.detail());
            }
            if (result.remedy() != null && !result.remedy().isBlank()) {
                out.println("         -> " + result.remedy());
            }
        }

        out.println();
        out.printf("  %d ok, %d warning(s), %d failure(s), %d skipped.%n",
                this.count(CheckResult.Severity.PASS),
                this.count(CheckResult.Severity.WARN),
                this.count(CheckResult.Severity.FAIL),
                this.count(CheckResult.Severity.SKIP));

        if (this.strict && !this.healthy() && this.count(CheckResult.Severity.FAIL) == 0) {
            out.println("  (--strict: warnings and skipped checks count as failures)");
        }
    }
}
