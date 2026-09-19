package dev.watchwolf.cli.doctor;

/**
 * One diagnostic finding.
 *
 * <p>{@link Severity#SKIP} is a first-class outcome, not a quiet pass. A check that could not run
 * -- no compatibility matrix shipped, no Tester checkout -- must say so rather than reporting
 * green, because "it passed" and "it never ran" are very different things to read in a bug report.
 */
public record CheckResult(Severity severity, String what, String detail, String remedy) {

    public enum Severity {
        PASS("ok  "), WARN("warn"), FAIL("FAIL"), SKIP("skip");

        private final String label;

        Severity(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

    public static CheckResult pass(String what, String detail) {
        return new CheckResult(Severity.PASS, what, detail, null);
    }

    public static CheckResult warn(String what, String detail, String remedy) {
        return new CheckResult(Severity.WARN, what, detail, remedy);
    }

    public static CheckResult fail(String what, String detail, String remedy) {
        return new CheckResult(Severity.FAIL, what, detail, remedy);
    }

    public static CheckResult skip(String what, String detail) {
        return new CheckResult(Severity.SKIP, what, detail, null);
    }

    public boolean isFailure() {
        return this.severity == Severity.FAIL;
    }
}
