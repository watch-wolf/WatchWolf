package dev.watchwolf.cli.proc;

import dev.watchwolf.cli.progress.ProgressSink;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Clone and update a repository, and answer whether the result is really a clone.
 *
 * <p>The update path matters as much as the clone: {@code build} must be idempotent and must never
 * delete a directory it did not create, so a second run fetches and fast-forwards rather than
 * removing and re-cloning the way {@code WatchWolfSetup.sh --build} did.
 */
public final class GitRepository {
    private static final Duration CLONE_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration QUICK_TIMEOUT = Duration.ofSeconds(30);

    private final CommandRunner commands;
    private final Path directory;

    public GitRepository(CommandRunner commands, Path directory) {
        this.commands = commands;
        this.directory = directory;
    }

    public CommandResult clone(String url, String branch, ProgressSink progress) {
        // name the host: "cloning" alone is useless when github.com is what is unreachable
        progress.begin("Cloning " + shortName(url) + " from " + hostOf(url)
                + " (branch " + branch + ")");
        CommandResult result = this.commands.run(this.directory.getParent(), Map.of(),
                CLONE_TIMEOUT, progress,
                List.of("git", "clone", "--branch", branch, url, this.directory.toString()));
        progress.end(result.succeeded() ? "cloned" : "failed");
        return result;
    }

    /** Fetch and fast-forward an existing clone onto {@code branch}. */
    public CommandResult update(String branch, ProgressSink progress) {
        progress.begin("Updating " + this.directory.getFileName() + " to branch " + branch);
        CommandResult fetch = this.run(CLONE_TIMEOUT, progress, "git", "fetch", "--prune", "origin");
        if (!fetch.succeeded()) {
            progress.end("failed");
            return fetch;
        }
        CommandResult checkout = this.run(QUICK_TIMEOUT, progress, "git", "checkout", branch);
        if (!checkout.succeeded()) {
            progress.end("failed");
            return checkout;
        }
        CommandResult merge =
                this.run(CLONE_TIMEOUT, progress, "git", "merge", "--ff-only", "origin/" + branch);
        progress.end(merge.succeeded() ? "updated" : "failed");
        return merge;
    }

    /**
     * Fetches {@code branch} from origin and fast-forwards onto it -- and only when that is a
     * <b>clean</b> fast-forward. Unlike {@link #update}, this never switches branches and never
     * attempts a real (non-fast-forward) merge, so commits local to this checkout that origin
     * does not have -- active development on this exact checkout, for instance -- are always left
     * exactly as they are. There is nothing here that can lose, rewrite, or conflict-merge local
     * work; the worst that happens is {@link PullOutcome.Diverged} being reported instead of
     * applying anything.
     */
    public PullOutcome pullFastForwardOnly(String branch, ProgressSink progress) {
        progress.begin("Checking " + this.directory.getFileName() + " for updates on " + branch);

        CommandResult fetch =
                this.run(CLONE_TIMEOUT, progress, "git", "fetch", "--prune", "origin", branch);
        if (!fetch.succeeded()) {
            progress.end("failed");
            return new PullOutcome.Failed(fetch.failureText());
        }

        Optional<String> local = this.revParse("HEAD");
        Optional<String> remote = this.revParse("origin/" + branch);
        if (local.isEmpty() || remote.isEmpty()) {
            progress.end("failed");
            return new PullOutcome.Failed("could not resolve HEAD or origin/" + branch);
        }

        if (local.get().equals(remote.get()) || this.isAncestor(remote.get(), "HEAD")) {
            // origin is at or behind local -- nothing to pull, even if local is ahead of it (e.g.
            // active development that started from what is still origin's tip)
            progress.end("already up to date");
            return new PullOutcome.UpToDate();
        }

        if (!this.isAncestor("HEAD", remote.get())) {
            // neither side is an ancestor of the other: both have commits the other lacks
            progress.end("diverged");
            return new PullOutcome.Diverged(shortSha(local.get()), shortSha(remote.get()));
        }

        CommandResult merge =
                this.run(CLONE_TIMEOUT, progress, "git", "merge", "--ff-only", "origin/" + branch);
        if (!merge.succeeded()) {
            // the ancestor check above should make this unreachable in practice; kept as a safety
            // net (e.g. a dirty working tree the fast-forward would have to overwrite) rather than
            // mislabelling an unrelated failure as "diverged"
            progress.end("failed");
            return new PullOutcome.Failed(merge.failureText());
        }
        String newSha = this.headSha().orElse(shortSha(remote.get()));
        progress.end("updated to " + newSha);
        return new PullOutcome.FastForwarded(shortSha(local.get()), newSha);
    }

    private Optional<String> revParse(String ref) {
        CommandResult result = this.run(QUICK_TIMEOUT, null, "git", "rev-parse", ref);
        if (!result.succeeded() || result.stdout().isEmpty()) return Optional.empty();
        return Optional.of(result.stdout().get(0).strip());
    }

    private boolean isAncestor(String ancestor, String descendant) {
        return this.run(QUICK_TIMEOUT, null, "git", "merge-base", "--is-ancestor",
                ancestor, descendant).succeeded();
    }

    private static String shortSha(String fullSha) {
        return fullSha.length() > 7 ? fullSha.substring(0, 7) : fullSha;
    }

    /** The result of {@link #pullFastForwardOnly}. */
    public sealed interface PullOutcome {
        /** Nothing to pull: origin is at or behind local. Local may be ahead; untouched either way. */
        record UpToDate() implements PullOutcome { }

        /** A clean fast-forward was applied. */
        record FastForwarded(String fromSha, String toSha) implements PullOutcome { }

        /** Both sides have commits the other lacks. Nothing was touched. */
        record Diverged(String localSha, String remoteSha) implements PullOutcome { }

        /** The fetch failed, or an unexpected error prevented checking/applying the update. */
        record Failed(String reason) implements PullOutcome { }
    }

    public boolean looksLikeAClone() {
        CommandResult result = this.run(QUICK_TIMEOUT, null, "git", "rev-parse", "--git-dir");
        return result.succeeded();
    }

    public Optional<String> currentBranch() {
        CommandResult result =
                this.run(QUICK_TIMEOUT, null, "git", "rev-parse", "--abbrev-ref", "HEAD");
        if (!result.succeeded() || result.stdout().isEmpty()) return Optional.empty();
        return Optional.of(result.stdout().get(0).strip());
    }

    public Optional<String> headSha() {
        CommandResult result = this.run(QUICK_TIMEOUT, null, "git", "rev-parse", "--short", "HEAD");
        if (!result.succeeded() || result.stdout().isEmpty()) return Optional.empty();
        return Optional.of(result.stdout().get(0).strip());
    }

    /** Uncommitted changes: a warning, never a failure -- a developer may be mid-edit. */
    public boolean isDirty() {
        CommandResult result = this.run(QUICK_TIMEOUT, null, "git", "status", "--porcelain");
        return result.succeeded() && !result.stdout().isEmpty();
    }

    private CommandResult run(Duration timeout, ProgressSink progress, String... argv) {
        return this.commands.run(this.directory, Map.of(), timeout,
                progress == null ? dev.watchwolf.cli.progress.ProgressSink.discarding() : progress,
                List.of(argv));
    }

    public static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (RuntimeException ex) {
            return url;
        }
    }

    public static String shortName(String url) {
        String trimmed = url.endsWith(".git") ? url.substring(0, url.length() - 4) : url;
        int slash = trimmed.lastIndexOf('/');
        return slash < 0 ? trimmed : trimmed.substring(slash + 1);
    }
}
