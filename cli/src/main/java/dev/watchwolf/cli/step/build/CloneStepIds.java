package dev.watchwolf.cli.step.build;

import dev.watchwolf.cli.step.StepId;

/** The clone steps' ids, so other steps can depend on them without a circular reference. */
public final class CloneStepIds {
    public static final StepId SERVERS_MANAGER = StepId.of("clone-servers-manager");
    public static final StepId CLIENTS_MANAGER = StepId.of("clone-clients-manager");
    public static final StepId TESTER = StepId.of("clone-tester");

    private CloneStepIds() {
    }
}
