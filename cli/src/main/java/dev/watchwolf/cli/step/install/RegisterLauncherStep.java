package dev.watchwolf.cli.step.install;

import dev.watchwolf.cli.step.*;

import java.util.Set;

/**
 * Makes {@code watchwolf} available everywhere.
 *
 * <p>The CLI runs as an unprivileged container user, so it cannot create a symlink in
 * {@code /usr/local/bin} itself. It renders the command instead; the process exits with
 * {@link dev.watchwolf.cli.ExitCodes#HOST_ACTION_REQUIRED} and the launcher shows the script and
 * asks. The user sees the exact root command before it runs, which is more honest than a
 * {@code sudo} buried in a build.
 */
public final class RegisterLauncherStep implements Step {
    public static final StepId ID = StepId.of("register-launcher");
    public static final String TARGET = "/usr/local/bin/watchwolf";

    @Override public StepId id()    { return ID; }
    @Override public String title() { return "Make 'watchwolf' available from anywhere"; }

    @Override
    public boolean isApplicable(StepContext context) {
        return context.plan().registerLauncher();
    }

    @Override
    public void perform(StepContext context) throws StepFailedException {
        String launcher = context.layout().stateDir().resolve("watchwolf").toString();

        context.hostAction()
                .add("Install the launcher so 'watchwolf' works from any directory.",
                        "install -m 0755 " + HostAction.quote(launcher) + " " + TARGET)
                .requiringRoot();

        context.progress().detail("queued: symlink " + TARGET);
    }

    @Override
    public Verification verification() {
        return new Verification() {
            @Override
            public String describe() {
                return TARGET + " exists and is executable";
            }

            @Override
            public void check(StepContext context) throws VerificationFailedException {
                java.nio.file.Path target = java.nio.file.Paths.get(TARGET);
                if (!context.files().exists(target)) {
                    throw new VerificationFailedException(
                            "the launcher is not installed",
                            TARGET + " does not exist",
                            "Run 'watchwolf install' and accept the host action it prints.");
                }
            }
        };
    }
}
