package dev.watchwolf.cli.fake;

import dev.watchwolf.cli.proc.CommandResult;
import dev.watchwolf.cli.proc.CommandRunner;
import dev.watchwolf.cli.progress.ProgressSink;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records the exact argv, cwd and environment a step used, and replays canned results.
 *
 * <p>Asserting the argv matters more than it sounds: the compose project name, the {@code --branch}
 * of a clone and the {@code --tests} pattern handed to the Tester are all things that are silently
 * wrong rather than loudly broken.
 */
public final class RecordingCommandRunner implements CommandRunner {

    public record Invocation(Path workingDirectory, Map<String, String> environment,
                             List<String> argv) {
        public String commandLine() {
            return String.join(" ", this.argv);
        }
    }

    private final List<Invocation> invocations = new ArrayList<>();
    private final Map<String, CommandResult> scripted = new LinkedHashMap<>();
    private CommandResult fallback = CommandResult.of(0, List.of(), List.of());

    /** Any invocation whose command line contains {@code fragment} returns {@code result}. */
    public RecordingCommandRunner respondTo(String fragment, CommandResult result) {
        this.scripted.put(fragment, result);
        return this;
    }

    public RecordingCommandRunner respondTo(String fragment, int exitCode, String stdout) {
        return this.respondTo(fragment,
                CommandResult.of(exitCode, stdout.isEmpty() ? List.of() : List.of(stdout.split("\n")),
                        List.of()));
    }

    public RecordingCommandRunner failingBy(int exitCode, String stderr) {
        this.fallback = CommandResult.of(exitCode, List.of(), List.of(stderr));
        return this;
    }

    public List<Invocation> invocations() {
        return List.copyOf(this.invocations);
    }

    public boolean ran(String fragment) {
        return this.invocations.stream().anyMatch(i -> i.commandLine().contains(fragment));
    }

    public Invocation lastInvocation() {
        if (this.invocations.isEmpty()) throw new IllegalStateException("nothing was run");
        return this.invocations.get(this.invocations.size() - 1);
    }

    @Override
    public CommandResult run(Path workingDirectory, Map<String, String> environment,
                             Duration timeout, ProgressSink progress, List<String> argv) {
        this.invocations.add(new Invocation(workingDirectory, Map.copyOf(environment),
                List.copyOf(argv)));

        String commandLine = String.join(" ", argv);
        for (Map.Entry<String, CommandResult> entry : this.scripted.entrySet()) {
            if (commandLine.contains(entry.getKey())) return entry.getValue();
        }
        return this.fallback;
    }
}
