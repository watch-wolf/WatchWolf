package dev.watchwolf.cli.docker;

import dev.watchwolf.cli.progress.ProgressSink;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Everything the CLI asks of Docker, behind one interface.
 *
 * <p>This exists so the unit suite can construct any environment -- three servers and two bots, a
 * dead ServersManager, a container with a malformed name -- in milliseconds and with no daemon.
 * The cautionary example is right next door: {@code DockerizedServerInstantiator} builds a client
 * ad hoc in five separate places, never closes one, and is consequently untestable without a live
 * daemon. Everything here is constructor-injected instead, with {@code DockerJavaFacade} for real
 * and {@code FakeDockerFacade} for tests.
 *
 * <p>Implementations must be safe to call from the monitor's poller thread.
 */
public interface DockerFacade extends AutoCloseable {

    DaemonInfo daemonInfo();

    /** Every container, running or not. Names come back normalised (no leading slash). */
    List<ContainerSnapshot> listContainers();

    default Optional<ContainerSnapshot> findContainer(String name) {
        return this.listContainers().stream().filter(c -> name.equals(c.name())).findFirst();
    }

    /** Containers whose name starts with the prefix, e.g. {@code MC_Server-}. */
    default List<ContainerSnapshot> containersNamed(String prefix) {
        return this.listContainers().stream().filter(c -> c.name().startsWith(prefix)).toList();
    }

    boolean imageExists(String reference);

    void pullImage(String reference, ProgressSink progress);

    /** {@code docker build --tag <tag> <contextPath>}. */
    void buildImage(String contextPath, String tag, ProgressSink progress);

    /** The last {@code tail} lines of a container's stdout/stderr. */
    List<String> logs(String container, int tail);

    /**
     * Follows a container's output until {@code stop} is signalled.
     *
     * @return a handle that stops the stream when closed
     */
    AutoCloseable followLogs(String container, int tail, Consumer<String> onLine);

    /** CPU and memory for a running container. Empty when the daemon cannot produce them. */
    Optional<ContainerStats> stats(String container);

    /** Runs a command inside a running container and returns its stdout. */
    String exec(String container, String... argv);

    void stopContainer(String container, int timeoutSeconds);

    void removeContainer(String container, boolean force);

    /** Starts a container detached; returns its id. */
    String runDetached(RunSpec spec);

    /** Runs a container to completion and returns its exit code, streaming output to the sink. */
    int runToCompletion(RunSpec spec, Consumer<String> onLine);

    @Override
    void close();

    /**
     * CPU percentage and memory bytes.
     *
     * <p>Both are frequently unavailable (rootless daemons, cgroup v1). Callers must render
     * {@code n/a} in that case rather than a fabricated {@code 0.0%}.
     */
    record ContainerStats(double cpuPercent, long memoryBytes, long memoryLimitBytes) {
        public String humanMemory() {
            return humanBytes(this.memoryBytes);
        }

        public static String humanBytes(long bytes) {
            if (bytes <= 0) return "n/a";
            if (bytes < 1024L * 1024) return Math.round(bytes / 1024.0) + "K";
            if (bytes < 1024L * 1024 * 1024) return Math.round(bytes / (1024.0 * 1024)) + "M";
            return String.format("%.1fG", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
