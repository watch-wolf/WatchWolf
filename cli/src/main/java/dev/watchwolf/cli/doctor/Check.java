package dev.watchwolf.cli.doctor;

import dev.watchwolf.cli.step.StepContext;

/** One tier-1 diagnostic. Fast, read-only, and safe to run against a live environment. */
public interface Check {
    String name();

    CheckResult run(StepContext context);
}
