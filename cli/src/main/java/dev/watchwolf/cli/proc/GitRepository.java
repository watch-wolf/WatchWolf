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
