package dev.watchwolf.cli.step.build;

import dev.watchwolf.cli.docker.DaemonInfo;
import dev.watchwolf.cli.docker.RunSpec;
import dev.watchwolf.cli.step.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Proves Docker works <em>and</em> that the install base is visible to the daemon at the same path.
 *
 * <p>The second half is the highest-value check in the whole install. The CLI runs in a container
 * but hands the daemon bind-mount sources like {@code -v <base>/...:/Versions}, and the daemon
 * resolves those on the <b>host</b>. The launcher makes that safe by mounting the base at its own
 * absolute path -- but if the base is behind a symlink, or Docker Desktop is not sharing that
 * directory, the mount silently resolves to an empty directory and every build produces nothing,
 * with no error anywhere.
 *
 * <p>A sentinel file and one {@code busybox test -f} settles it in about 200ms.
 */
public final class PreflightDockerStep implements Step {
    public static final StepId ID = StepId.of("preflight-docker");

    @Override public StepId id()    { return ID; }
    @Override public String title() { return "Check Docker and the install path"; }

    @Override
    public void perform(StepContext context) throws StepFailedException {
        context.progress().begin("Checking the Docker daemon");
        DaemonInfo daemon = context.docker().daemonInfo();
        if (!daemon.reachable()) {
            context.progress().end("unreachable");
            throw new StepFailedException(
                    "connecting to Docker",
                    daemon.unreachableReason(),
                    "Start Docker, and make sure " + System.getProperty("user.name", "your user")
                            + " can use its socket (sudo usermod -aG docker $USER, then log out "
                            + "and back in). See https://docs.docker.com/get-docker/.");
        }
        context.progress().end("Docker " + daemon.serverVersion()
                + " (API " + daemon.apiVersion() + ")");

        if (!daemon.hostNetworkingIsTruthful()) {
            context.progress().warn("This is Docker Desktop, where --network host does not expose "
                    + "the host's interfaces. Network diagnostics will describe the container's "
                    + "view, and will say so.");
        }

        this.assertBaseIsVisibleToTheDaemon(context);
    }

    /** @see PreflightDockerStep the class comment, for why this is worth 200ms on every run */
    private void assertBaseIsVisibleToTheDaemon(StepContext context) throws StepFailedException {
        Path base = context.layout().base();
        String token = "sentinel-" + UUID.randomUUID();
        Path sentinel = context.layout().stateDir().resolve(token);

        context.progress().begin("Checking the daemon sees " + base + " at the same path");
        try {
            context.files().createDirectories(context.layout().stateDir());
            context.files().writeString(sentinel, "watchwolf identity-mount check\n");
        } catch (IOException ex) {
            context.progress().end("failed");
            throw new StepFailedException(
                    "writing to the install path",
                    base + " is not writable: " + ex.getMessage(),
                    "Choose a writable --path, or fix the ownership of " + base + ".");
        }

        try {
            int exitCode = context.docker().runToCompletion(
                    RunSpec.of("busybox")
                            .bind(base.toString(), base.toString())
                            .withEntrypoint("test")
                            .withCommand("-f", sentinel.toString()),
                    null);

            if (exitCode != 0) {
                context.progress().end("mismatch");
                throw new StepFailedException(
                        "checking the install path",
                        base + " is not visible to the Docker daemon at that same path",
                        "Bind mounts are resolved by the daemon on the host, so every build would "
                                + "silently produce nothing. This usually means " + base + " is a "
                                + "symlink, or that Docker Desktop is not sharing it. Pass --path "
                                + "pointing at a real, shared directory.");
            }
            context.progress().end("visible");
        } finally {
            try {
                context.files().delete(sentinel);
            } catch (IOException ignored) {
                // a stray sentinel file is harmless
            }
        }
    }

    @Override
    public Verification verification() {
        return new Verification() {
            @Override
            public String describe() {
                return "the Docker daemon answers and the install path resolves identically inside "
                        + "and outside the container";
            }

            @Override
            public void check(StepContext context) throws VerificationFailedException {
                DaemonInfo daemon = context.docker().daemonInfo();
                if (!daemon.reachable()) {
                    throw new VerificationFailedException(
                            "Docker is not reachable",
                            daemon.unreachableReason(),
                            "Start Docker and run the command again.");
                }
            }
        };
    }
}
