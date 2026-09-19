package dev.watchwolf.cli.step.build;

import dev.watchwolf.cli.proc.CommandResult;
import dev.watchwolf.cli.proc.GitRepository;
import dev.watchwolf.cli.proc.ProcessCommandRunner;
import dev.watchwolf.cli.progress.ProgressSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves {@link GitRepository} -- which {@link CloneStep}'s postcondition is built from -- against
 * a real clone and a real, deliberately corrupted one. Needs network; small and fast enough
 * (WatchWolf-Tester's {@code dev} branch is ~15MB and clones in under two seconds from this
 * network) not to be worth mocking away.
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
public class ITGitRepositoryShould {
    private static final String URL = "https://github.com/rogermiranda1000/WatchWolf-Tester.git";

    @TempDir
    Path base;

    @Test
    void cloneARealRepositoryOnTheRequestedBranch() {
        Path directory = base.resolve("WatchWolf-Tester");
        GitRepository git = new GitRepository(new ProcessCommandRunner(), directory);

        CommandResult result = git.clone(URL, "dev", ProgressSink.discarding());

        assertTrue(result.succeeded(), result.failureText());
        assertTrue(Files.isDirectory(directory.resolve(".git")));
        assertTrue(Files.exists(directory.resolve("ci/tests.sh")),
                "a clone that exited 0 must actually have the files CloneStep's verification "
                        + "checks for");
        assertTrue(git.looksLikeAClone());
        assertEquals("dev", git.currentBranch().orElseThrow());
    }

    @Test
    void reportItDoesNotLookLikeACloneWhenTheGitDirIsMissing() throws Exception {
        // this is exactly the "git clone exited 0 but left a partial tree" case
        // CloneStep's verification exists to catch
        Path directory = base.resolve("half-a-clone");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("some-file.txt"), "not actually a git checkout");

        GitRepository git = new GitRepository(new ProcessCommandRunner(), directory);

        assertFalse(git.looksLikeAClone());
        assertTrue(git.currentBranch().isEmpty());
    }

    @Test
    void updateAnExistingCloneInPlaceRatherThanFail() {
        Path directory = base.resolve("WatchWolf-Tester");
        GitRepository git = new GitRepository(new ProcessCommandRunner(), directory);
        assertTrue(git.clone(URL, "dev", ProgressSink.discarding()).succeeded());

        // this is the idempotent-build path: a second run must fetch and fast-forward, not
        // delete and re-clone the way WatchWolfSetup.sh --build used to
        CommandResult updated = git.update("dev", ProgressSink.discarding());

        assertTrue(updated.succeeded(), updated.failureText());
        assertTrue(Files.isDirectory(directory.resolve(".git")));
    }

    @Test
    void reportAFailedCloneWithoutThrowing() {
        Path directory = base.resolve("nonexistent-repo");
        GitRepository git = new GitRepository(new ProcessCommandRunner(), directory);

        CommandResult result = git.clone(
                "https://github.com/watch-wolf/this-repo-does-not-exist-i-hope.git",
                "dev", ProgressSink.discarding());

        assertFalse(result.succeeded());
        assertFalse(result.failureText().isBlank(), "a failed clone must explain itself");
    }
}
