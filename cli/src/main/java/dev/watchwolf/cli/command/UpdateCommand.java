package dev.watchwolf.cli.command;

import dev.watchwolf.cli.ExitCodes;
import dev.watchwolf.cli.proc.GitRepository;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

/**
 * Updates this checkout, and -- if an install exists -- the ServersManager and ClientsManager
 * clones it made.
 *
 * <p>Every repository is updated the same safe way,
 * {@link GitRepository#pullFastForwardOnly}: fetch, then fast-forward if and only if that is
 * clean. Nothing here ever checks out a different branch, attempts a real merge, or discards
 * anything. That is deliberate for this checkout specifically -- it may have active development
 * on it, commits ahead of origin that a naive {@code git pull} could otherwise rebase, merge, or
 * conflict against. A checkout that has simply drifted ahead of an unmoved origin is reported as
 * up to date; a checkout that has genuinely diverged (both sides hold commits the other lacks) is
 * reported and left exactly as it is, never merged.
 *
 * <p>The image is rebuilt after checking this checkout specifically -- unconditionally, not only
 * when the pull actually fast-forwarded. "Make the image match what's on disk" is the point of
 * running this here, and the working tree can carry local edits (committed or not, pushed or not)
 * that {@code git fetch} would never see; gating the rebuild on a fast-forward having happened
 * would silently skip exactly that case.
 */
@Command(name = "update",
        header = "Update this checkout, and any install's ServersManager/ClientsManager clones.",
        description = {
                "Fast-forwards only -- never merges, rebases, switches branches, or discards local",
                "work. A checkout with commits the remote does not have is reported, not touched.",
                "ServersManager and ClientsManager, if installed, are each updated to whatever",
                "branch they are already checked out on."
        })
public class UpdateCommand implements Callable<Integer> {

    @Mixin
    GlobalOptions options = new GlobalOptions();

    @Override
    public Integer call() {
        try (CliContext cli = new CliContext(this.options)) {
            boolean ok = this.updateCliCheckout(cli);

            boolean serversManagerInstalled =
                    cli.files().isDirectory(cli.layout().serversManagerRepo().resolve(".git"));
            boolean clientsManagerInstalled =
                    cli.files().isDirectory(cli.layout().clientsManagerRepo().resolve(".git"));

            if (serversManagerInstalled) {
                ok &= this.updateExistingClone(cli, "ServersManager",
                        cli.layout().serversManagerRepo());
            }
            if (clientsManagerInstalled) {
                ok &= this.updateExistingClone(cli, "ClientsManager",
                        cli.layout().clientsManagerRepo());
            }
            if (!serversManagerInstalled && !clientsManagerInstalled) {
                System.out.println("[i] No install found at " + cli.layout().base()
                        + " -- nothing there to update.");
            }

            return ok ? ExitCodes.OK : ExitCodes.ERROR;
        }
    }

    /** @return false only on a hard failure (network/git error) */
    private boolean updateCliCheckout(CliContext cli) {
        String repoRootEnv = System.getenv("WW_REPO_ROOT");
        if (repoRootEnv == null || repoRootEnv.isBlank()) {
            System.out.println("[i] WatchWolf CLI: not running from a git checkout (no "
                    + "WW_REPO_ROOT) -- nothing to self-update. Run 'watchwolf' via cli/watchwolf "
                    + "from a checkout of the repo, not a standalone container.");
            return true;
        }

        Path repoRoot = Paths.get(repoRootEnv);
        GitRepository repo = new GitRepository(cli.commands(), repoRoot);
        String branch = repo.currentBranch().orElse(null);
        if (branch == null) {
            System.out.println("[w] WatchWolf CLI: " + repoRoot
                    + " does not look like a git checkout -- nothing to self-update.");
            return true;
        }

        GitRepository.PullOutcome outcome = repo.pullFastForwardOnly(branch, cli.progress());
        boolean ok = this.report("WatchWolf CLI (this checkout)", outcome);
        // Always rebuild, not only after a fast-forward: the point of running this on THIS
        // checkout is "make the image match what's on disk right now", and the working tree can
        // carry local, uncommitted changes -- or committed-but-unpushed ones -- that a pull would
        // never see in the first place. Not free -- docker-java's buildImageCmd goes through the
        // classic builder, not BuildKit, so it does not reuse a `docker build` CLI run's cache and
        // takes about a minute even with nothing changed -- but skipping it when something did
        // change is the real cost, and this command is not run in a tight loop.
        this.rebuildImage(cli, repoRoot);
        return ok;
    }

    private void rebuildImage(CliContext cli, Path repoRoot) {
        String image = System.getenv("WW_IMAGE");
        if (image == null || image.isBlank()) image = "watchwolf-cli:local";
        Path contextPath = repoRoot.resolve("cli");
        try {
            cli.docker().buildImage(contextPath.toString(), image, cli.progress());
            System.out.println("[v] Rebuilt " + image + " -- the next 'watchwolf' run will use it.");
        } catch (RuntimeException ex) {
            System.err.println("[e] Updated the checkout, but could not rebuild " + image + ": "
                    + ex.getMessage());
            System.err.println("[e] Run 'docker build --tag " + image + " " + contextPath
                    + "' by hand.");
        }
    }

    /** @return false only on a hard failure (network/git error) */
    private boolean updateExistingClone(CliContext cli, String name, Path directory) {
        GitRepository repo = new GitRepository(cli.commands(), directory);
        String branch = repo.currentBranch().orElse(null);
        if (branch == null) {
            System.out.println("[w] " + name + ": " + directory
                    + " has a .git directory but no current branch -- left alone.");
            return true;
        }

        // "the same branch as the original install" -- the clone's own current branch, not a
        // stored config, so this also works for an install made before this command existed
        GitRepository.PullOutcome outcome = repo.pullFastForwardOnly(branch, cli.progress());
        return this.report(name, outcome);
    }

    /** @return false only for a hard failure */
    private boolean report(String name, GitRepository.PullOutcome outcome) {
        if (outcome instanceof GitRepository.PullOutcome.UpToDate) {
            System.out.println("[i] " + name + ": no changes.");
            return true;
        }
        if (outcome instanceof GitRepository.PullOutcome.FastForwarded fastForwarded) {
            System.out.println("[v] " + name + ": updated " + fastForwarded.fromSha() + " -> "
                    + fastForwarded.toSha() + ".");
            return true;
        }
        if (outcome instanceof GitRepository.PullOutcome.Diverged diverged) {
            System.out.println("[w] " + name + ": diverged from the remote (local "
                    + diverged.localSha() + ", remote " + diverged.remoteSha()
                    + ") -- applying this would need a real merge and could conflict, so nothing "
                    + "was touched. Merge or rebase by hand if you want the remote changes.");
            return true;
        }
        GitRepository.PullOutcome.Failed failed = (GitRepository.PullOutcome.Failed) outcome;
        System.err.println("[e] " + name + ": could not check for updates: " + failed.reason());
        return false;
    }
}
