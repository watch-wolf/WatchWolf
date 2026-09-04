package dev.watchwolf.cli.proc;

import dev.watchwolf.cli.progress.ProgressSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves {@link GitRepository#pullFastForwardOnly} against the real {@code git} binary, using a
 * local bare repository as "origin" so every scenario -- clean fast-forward, local ahead of an
 * unmoved origin, and a genuine divergence -- is exact and needs no network.
 *
 * <p>This is what {@code watchwolf update} relies on to be safe to run on a checkout with active
 * development on it: see that class's Javadoc.
 */
@Timeout(value = 1, unit = TimeUnit.MINUTES)
public class ITGitRepositoryPullFastForwardOnlyShould {
    @TempDir
    Path base;

    private Path origin;
    private Path clone;

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        this.origin = this.base.resolve("origin.git");
        exec(this.base, "git", "init", "--bare", "--initial-branch=main", this.origin.toString());

        Path seed = this.base.resolve("seed");
        exec(this.base, "git", "init", "--initial-branch=main", seed.toString());
        exec(seed, "git", "config", "user.email", "test@example.invalid");
        exec(seed, "git", "config", "user.name", "Test");
        Files.writeString(seed.resolve("file.txt"), "first\n");
        exec(seed, "git", "add", "file.txt");
        exec(seed, "git", "commit", "-m", "first");
        exec(seed, "git", "remote", "add", "origin", this.origin.toString());
        exec(seed, "git", "push", "origin", "main");

        this.clone = this.base.resolve("clone");
        exec(this.base, "git", "clone", this.origin.toString(), this.clone.toString());
        exec(this.clone, "git", "config", "user.email", "test@example.invalid");
        exec(this.clone, "git", "config", "user.name", "Test");
    }

    private GitRepository repo() {
        return new GitRepository(new ProcessCommandRunner(), this.clone);
    }

    @Test
    void reportUpToDateWhenNothingChanged() {
        GitRepository.PullOutcome outcome =
                this.repo().pullFastForwardOnly("main", ProgressSink.discarding());

        assertInstanceOf(GitRepository.PullOutcome.UpToDate.class, outcome);
    }

    @Test
    void fastForwardCleanlyWhenOriginGainsCommits() throws IOException, InterruptedException {
        this.commitAndPushToOrigin("second");

        GitRepository.PullOutcome outcome =
                this.repo().pullFastForwardOnly("main", ProgressSink.discarding());

        assertInstanceOf(GitRepository.PullOutcome.FastForwarded.class, outcome);
        assertTrue(Files.readString(this.clone.resolve("file.txt")).contains("second"),
                "the fast-forward must actually update the working tree");
    }

    @Test
    void reportUpToDateWhenLocalHasCommitsOriginDoesNotHaveYet() throws IOException,
            InterruptedException {
        // this is the guarantee 'watchwolf update' depends on: active development on this exact
        // checkout, with origin unmoved since it branched, must be left alone -- never reported
        // as needing a merge, and never touched
        Files.writeString(this.clone.resolve("local-work.txt"), "in progress\n");
        exec(this.clone, "git", "add", "local-work.txt");
        exec(this.clone, "git", "commit", "-m", "local development");

        GitRepository.PullOutcome outcome =
                this.repo().pullFastForwardOnly("main", ProgressSink.discarding());

        assertInstanceOf(GitRepository.PullOutcome.UpToDate.class, outcome);
        assertTrue(Files.exists(this.clone.resolve("local-work.txt")),
                "the local commit must still be there");
    }

    @Test
    void reportDivergedWhenBothSidesMovedAndTouchNothing() throws IOException,
            InterruptedException {
        this.commitAndPushToOrigin("origin moved on");

        Files.writeString(this.clone.resolve("local-work.txt"), "in progress\n");
        exec(this.clone, "git", "add", "local-work.txt");
        exec(this.clone, "git", "commit", "-m", "local development");
        String localHeadBefore = capture(this.clone, "git", "rev-parse", "HEAD");

        GitRepository.PullOutcome outcome =
                this.repo().pullFastForwardOnly("main", ProgressSink.discarding());

        assertInstanceOf(GitRepository.PullOutcome.Diverged.class, outcome);
        assertEquals(localHeadBefore, capture(this.clone, "git", "rev-parse", "HEAD"),
                "a divergence must never move HEAD");
        assertTrue(Files.exists(this.clone.resolve("local-work.txt")),
                "the local commit must still be there, untouched");
    }

    private void commitAndPushToOrigin(String content) throws IOException, InterruptedException {
        Path seed = this.base.resolve("seed");
        Files.writeString(seed.resolve("file.txt"), content + "\n");
        exec(seed, "git", "commit", "-am", content);
        exec(seed, "git", "push", "origin", "main");
    }

    private static void exec(Path directory, String... command) throws IOException,
            InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), () -> String.join(" ", command) + " failed:\n" + output);
    }

    private static String capture(Path directory, String... command) throws IOException,
            InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes()).strip();
        assertEquals(0, process.waitFor(), () -> String.join(" ", command) + " failed:\n" + output);
        return output;
    }
}
