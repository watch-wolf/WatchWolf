package dev.watchwolf.cli.proc;

import dev.watchwolf.cli.progress.ProgressSink;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Runs host commands: {@code git}, {@code docker compose}, and the Tester's {@code ci/tests.sh}.
 *
 * <p>A seam so tests can assert the exact argv and working directory a step used, and replay a
 * canned exit code, without a subprocess. {@code RecordingCommandRunner} is the test double.
 */
public interface CommandRunner {

    /**
     * @param workingDirectory the cwd; matters for {@code docker compose}, which resolves
     *                         {@code ./server-types} against it
     * @param timeout          hard bound; a hung clone must not hang the installer
     */
    CommandResult run(Path workingDirectory, Map<String, String> environment,
                      Duration timeout, ProgressSink progress, List<String> argv);

    default CommandResult run(Path workingDirectory, ProgressSink progress, String... argv) {
        return this.run(workingDirectory, Map.of(), Duration.ofMinutes(30), progress, List.of(argv));
    }
}
