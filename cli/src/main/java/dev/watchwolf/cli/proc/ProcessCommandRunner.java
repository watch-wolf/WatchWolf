package dev.watchwolf.cli.proc;

import dev.watchwolf.cli.progress.ProgressSink;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Runs a real subprocess.
 *
 * <p>Two behaviours worth stating: output is drained on its own thread (a process whose pipe fills
 * up blocks forever otherwise), and every call is bounded by a timeout so a hung {@code git clone}
 * surfaces as a named failure rather than a silent stall.
 */
public final class ProcessCommandRunner implements CommandRunner {

    /**
     * Environment keys scrubbed from every child.
     *
     * <p>COMPOSE_PROJECT_NAME is the dangerous one: the ServersManager's image name is
     * {@code <project>-servers-manager}, and the project defaults to the directory name
     * ({@code release}). An inherited value silently renames the image and breaks every image
     * check downstream. We always pass {@code -p} explicitly too -- belt and braces.
     */
    private static final List<String> SCRUBBED_PREFIXES = List.of("COMPOSE_");

    @Override
    public CommandResult run(Path workingDirectory, Map<String, String> environment,
                             Duration timeout, ProgressSink progress, List<String> argv) {
        ProcessBuilder builder = new ProcessBuilder(argv);
        if (workingDirectory != null) builder.directory(workingDirectory.toFile());

        Map<String, String> childEnvironment = builder.environment();
        childEnvironment.keySet().removeIf(ProcessCommandRunner::isScrubbed);
        childEnvironment.putAll(environment);

        List<String> stdout = new CopyOnWriteArrayList<>();
        List<String> stderr = new CopyOnWriteArrayList<>();

        try {
            Process process = builder.start();

            Thread outReader = drain(process.getInputStream(), stdout, progress);
            Thread errReader = drain(process.getErrorStream(), stderr, null);

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                outReader.join(1000);
                errReader.join(1000);
                return CommandResult.of(-1, stdout,
                        append(stderr, "timed out after " + timeout.toSeconds() + "s: "
                                + String.join(" ", argv)));
            }
            outReader.join(5000);
            errReader.join(5000);
            return CommandResult.of(process.exitValue(), stdout, stderr);

        } catch (IOException ex) {
            return CommandResult.of(-1, stdout,
                    append(stderr, "could not start '" + argv.get(0) + "': " + ex.getMessage()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return CommandResult.of(-1, stdout, append(stderr, "interrupted"));
        }
    }

    private static boolean isScrubbed(String key) {
        return SCRUBBED_PREFIXES.stream().anyMatch(key::startsWith);
    }

    private static List<String> append(List<String> lines, String extra) {
        List<String> copy = new ArrayList<>(lines);
        copy.add(extra);
        return copy;
    }

    private static Thread drain(java.io.InputStream stream, List<String> into, ProgressSink progress) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    into.add(line);
                    if (progress != null) progress.detail(line);
                }
            } catch (IOException ignored) {
                // the process died mid-read; the exit code is what matters
            }
        }, "command-output");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
