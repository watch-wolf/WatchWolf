package dev.watchwolf.cli.proc;

import dev.watchwolf.cli.fake.RecordingCommandRunner;
import dev.watchwolf.cli.progress.ProgressSink;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class GitRepositoryShould {
    private static final Path DIRECTORY = Paths.get("/repo");

    private static RecordingCommandRunner recorder() {
        return new RecordingCommandRunner().respondTo("git fetch", 0, "");
    }

    private static GitRepository repo(RecordingCommandRunner commands) {
        return new GitRepository(commands, DIRECTORY);
    }

    @Test
    public void reportUpToDateWhenOriginHasNothingNew() {
        RecordingCommandRunner commands = recorder()
                .respondTo("rev-parse HEAD", 0, "abc1234")
                .respondTo("rev-parse origin/main", 0, "abc1234");

        GitRepository.PullOutcome outcome =
                repo(commands).pullFastForwardOnly("main", ProgressSink.discarding());

        assertInstanceOf(GitRepository.PullOutcome.UpToDate.class, outcome);
        assertFalse(commands.ran("merge --ff-only"), "identical HEADs need no merge attempt");
    }

    @Test
    public void fastForwardCleanlyWhenOriginIsAhead() {
        RecordingCommandRunner commands = recorder()
                .respondTo("rev-parse HEAD", 0, "aaa1111")
                .respondTo("rev-parse origin/main", 0, "bbb2222")
                .respondTo("merge-base --is-ancestor bbb2222 HEAD", 1, "")   // origin not behind
                .respondTo("merge-base --is-ancestor HEAD bbb2222", 0, "")   // local is an ancestor
                .respondTo("merge --ff-only", 0, "")
                .respondTo("rev-parse --short HEAD", 0, "bbb2222");

        GitRepository.PullOutcome outcome =
                repo(commands).pullFastForwardOnly("main", ProgressSink.discarding());

        GitRepository.PullOutcome.FastForwarded forwarded =
                assertInstanceOf(GitRepository.PullOutcome.FastForwarded.class, outcome);
        assertEquals("aaa1111", forwarded.fromSha());
        assertEquals("bbb2222", forwarded.toSha());
        assertTrue(commands.ran("merge --ff-only origin/main"));
    }

    @Test
    public void reportUpToDateWhenLocalIsAheadAndOriginHasNotMoved() {
        // the exact case the command exists to get right: active development on this checkout,
        // with origin still at the commit that development branched from -- must not be reported
        // as a conflict, and must not touch anything
        RecordingCommandRunner commands = recorder()
                .respondTo("rev-parse HEAD", 0, "ccc3333")
                .respondTo("rev-parse origin/main", 0, "ddd4444")
                .respondTo("merge-base --is-ancestor ddd4444 HEAD", 0, "");   // origin is an ancestor

        GitRepository.PullOutcome outcome =
                repo(commands).pullFastForwardOnly("main", ProgressSink.discarding());

        assertInstanceOf(GitRepository.PullOutcome.UpToDate.class, outcome);
        assertFalse(commands.ran("merge --ff-only"), "local commits ahead of an unmoved origin must never "
                + "be merged, rebased, or otherwise touched");
    }

    @Test
    public void reportDivergedWithoutTouchingAnythingWhenBothSidesHaveUniqueCommits() {
        RecordingCommandRunner commands = recorder()
                .respondTo("rev-parse HEAD", 0, "eee5555")
                .respondTo("rev-parse origin/main", 0, "fff6666")
                .respondTo("merge-base --is-ancestor fff6666 HEAD", 1, "")
                .respondTo("merge-base --is-ancestor HEAD fff6666", 1, "");

        GitRepository.PullOutcome outcome =
                repo(commands).pullFastForwardOnly("main", ProgressSink.discarding());

        GitRepository.PullOutcome.Diverged diverged =
                assertInstanceOf(GitRepository.PullOutcome.Diverged.class, outcome);
        assertEquals("eee5555", diverged.localSha());
        assertEquals("fff6666", diverged.remoteSha());
        assertFalse(commands.ran("merge --ff-only"), "a divergence must never be merged automatically");
    }

    @Test
    public void reportFailedWhenTheFetchFails() {
        RecordingCommandRunner commands = new RecordingCommandRunner().respondTo("git fetch",
                CommandResult.of(1, List.of(), List.of("could not resolve host")));

        GitRepository.PullOutcome outcome =
                repo(commands).pullFastForwardOnly("main", ProgressSink.discarding());

        GitRepository.PullOutcome.Failed failed =
                assertInstanceOf(GitRepository.PullOutcome.Failed.class, outcome);
        assertEquals("could not resolve host", failed.reason());
    }

    @Test
    public void reportFailedWhenTheFinalMergeUnexpectedlyFails() {
        // the ancestor checks say a clean fast-forward should work, but git refuses anyway (e.g. a
        // dirty working tree the merge would have to overwrite) -- must be reported as a failure,
        // not mislabelled as "diverged", which is a history relationship, not this
        RecordingCommandRunner commands = recorder()
                .respondTo("rev-parse HEAD", 0, "1111aaa")
                .respondTo("rev-parse origin/main", 0, "2222bbb")
                .respondTo("merge-base --is-ancestor 2222bbb HEAD", 1, "")
                .respondTo("merge-base --is-ancestor HEAD 2222bbb", 0, "")
                .respondTo("merge --ff-only",
                        CommandResult.of(1, List.of(), List.of("local changes would be overwritten")));

        GitRepository.PullOutcome outcome =
                repo(commands).pullFastForwardOnly("main", ProgressSink.discarding());

        GitRepository.PullOutcome.Failed failed =
                assertInstanceOf(GitRepository.PullOutcome.Failed.class, outcome);
        assertEquals("local changes would be overwritten", failed.reason());
    }
}
