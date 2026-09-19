package dev.watchwolf.cli.step.build;

import dev.watchwolf.cli.step.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Creates the four directories the ServersManager's compose file bind-mounts.
 *
 * <p>They must exist before {@code docker compose up}: Docker creates a missing bind source
 * <b>as root</b>, which is how {@code logs/} and {@code tmp/} end up unreadable in a normal
 * install. Creating them first, as the invoking user, avoids that -- and where they are already
 * root-owned this warns and explains, since the CLI reads them through a short-lived root helper
 * container rather than sudo.
 */
public final class CreateRuntimeDirsStep implements Step {
    public static final StepId ID = StepId.of("create-runtime-dirs");

    @Override public StepId id()    { return ID; }
    @Override public String title() { return "Create the ServersManager runtime directories"; }

    @Override
    public Set<StepId> requires() {
        return Set.of(CloneStepIds.SERVERS_MANAGER);
    }

    private List<Path> directories(StepContext context) {
        return List.of(
                context.layout().serverTypes(),
                context.layout().serverTypes("Spigot"),
                context.layout().serverTypes("Paper"),
                context.layout().usualPlugins(),
                context.layout().tmp(),
                context.layout().logs());
    }

    @Override
    public void perform(StepContext context) throws StepFailedException {
        for (Path directory : this.directories(context)) {
            if (context.files().isDirectory(directory)) continue;
            try {
                context.files().createDirectories(directory);
            } catch (IOException ex) {
                throw new StepFailedException("creating " + directory, ex.getMessage(),
                        "Check the permissions on " + context.layout().serversManagerRuntime() + ".");
            }
        }

        for (Path directory : List.of(context.layout().logs(), context.layout().tmp())) {
            if (!context.files().isWritable(directory)) {
                context.progress().warn(directory + " is not writable by this user -- it was "
                        + "created by the ServersManager container, which runs as root. "
                        + "'watchwolf logs' reads it through a short-lived root helper container; "
                        + "no sudo is needed.");
            }
        }
    }

    @Override
    public Verification verification() {
        return new Verification() {
            @Override
            public String describe() {
                return "server-types/{Spigot,Paper}, usual-plugins/, tmp/ and logs/ all exist";
            }

            @Override
            public void check(StepContext context) throws VerificationFailedException {
                List<String> missing = new ArrayList<>();
                for (Path directory : CreateRuntimeDirsStep.this.directories(context)) {
                    if (!context.files().isDirectory(directory)) {
                        missing.add(context.layout().serversManagerRuntime()
                                .relativize(directory).toString());
                    }
                }
                if (!missing.isEmpty()) {
                    throw new VerificationFailedException(
                            "the ServersManager runtime directories are incomplete",
                            "missing " + String.join(", ", missing),
                            "Run 'watchwolf build'. The ServersManager's build.sh refuses to run "
                                    + "without server-types/ and usual-plugins/.");
                }
            }
        };
    }
}
