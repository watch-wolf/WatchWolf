package dev.watchwolf.cli.step.build;

import dev.watchwolf.cli.step.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Creates the install base and claims it, so nothing is ever deleted by surprise.
 *
 * <p>{@code WatchWolfSetup.sh --build} began with {@code sudo rm -rf "$servers_manager_path"} and
 * a {@code mktemp -d} dance to rescue {@code server-types/} and {@code usual-plugins/} first. This
 * replaces both: a marker file records that the CLI created this directory, an unrecognised
 * non-empty directory is refused rather than emptied, and later steps update in place instead of
 * re-cloning. Nothing is deleted, so nothing can be lost.
 */
public final class ResolveInstallBaseStep implements Step {
    public static final StepId ID = StepId.of("resolve-install-base");

    @Override public StepId id()    { return ID; }
    @Override public String title() { return "Prepare the install directory"; }

    @Override
    public java.util.Set<StepId> requires() {
        return java.util.Set.of(PreflightDockerStep.ID);
    }

    @Override
    public void perform(StepContext context) throws StepFailedException {
        Path base = context.layout().base();

        if (context.files().exists(base) && !context.files().isDirectory(base)) {
            throw new StepFailedException("preparing " + base, "it exists but is not a directory",
                    "Pass a different --path.");
        }

        boolean claimed = context.files().exists(context.layout().ownershipMarker());
        if (!claimed && context.files().isDirectory(base) && !this.looksLikeOurs(context, base)) {
            throw new StepFailedException(
                    "preparing " + base,
                    "it already exists, is not empty, and was not created by this command",
                    "This command refuses to write into a directory it does not recognise, so it "
                            + "can never delete something of yours. Pass an empty (or new) --path, "
                            + "or move the existing contents aside.");
        }

        try {
            context.files().createDirectories(context.layout().stateDir());
            context.files().writeString(context.layout().ownershipMarker(),
                    "Created by watchwolf. Removing this file makes 'watchwolf build' treat "
                            + base + " as somebody else's directory and refuse to touch it.\n");
        } catch (IOException ex) {
            throw new StepFailedException("claiming " + base, ex.getMessage(),
                    "Check the permissions on " + base + ".");
        }
    }

    /** Empty, or already holding the directories a previous install would have made. */
    private boolean looksLikeOurs(StepContext context, Path base) {
        List<Path> entries = context.files().list(base);
        if (entries.isEmpty()) return true;
        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            boolean known = name.equals("ServersManager") || name.equals("ClientsManager")
                    || name.equals("WatchWolf-Tester") || name.equals(".watchwolf")
                    || name.equals("dependencies");
            if (!known) return false;
        }
        return true;
    }

    @Override
    public Verification verification() {
        return new Verification() {
            @Override
            public String describe() {
                return "the install base exists, is writable, and carries this command's marker";
            }

            @Override
            public void check(StepContext context) throws VerificationFailedException {
                Path base = context.layout().base();
                if (!context.files().isDirectory(base)) {
                    throw new VerificationFailedException("the install base is missing",
                            base + " does not exist", "Run 'watchwolf build'.");
                }
                if (!context.files().isWritable(base)) {
                    throw new VerificationFailedException("the install base is not writable",
                            base + " cannot be written by this user",
                            "Fix its ownership, or pass a different --path.");
                }
                if (!context.files().exists(context.layout().ownershipMarker())) {
                    throw new VerificationFailedException("the install base is not claimed",
                            "no " + context.layout().ownershipMarker() + " marker",
                            "Run 'watchwolf build' to create it.");
                }
            }
        };
    }
}
