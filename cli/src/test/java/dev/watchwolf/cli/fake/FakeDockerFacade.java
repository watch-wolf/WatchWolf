package dev.watchwolf.cli.fake;

import dev.watchwolf.cli.docker.ContainerSnapshot;
import dev.watchwolf.cli.docker.DaemonInfo;
import dev.watchwolf.cli.docker.DockerFacade;
import dev.watchwolf.cli.docker.PortBindingInfo;
import dev.watchwolf.cli.docker.RunSpec;
import dev.watchwolf.cli.progress.ProgressSink;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * An in-memory Docker daemon.
 *
 * <p>Lets a test construct any environment -- three servers and two bots, a dead ServersManager, a
 * container with a malformed name -- in milliseconds and with no daemon, which is the entire reason
 * {@link DockerFacade} exists as an interface.
 *
 * <pre>
 * FakeDockerFacade docker = new FakeDockerFacade()
 *         .withContainer("ServersManager").running().publishing(8000, 8000).done()
 *         .withContainer("MC_Server-1772387923303").running()
 *                 .publishing(8001, 25565).publishing(8002, 25566).done();
 * </pre>
 */
public final class FakeDockerFacade implements DockerFacade {
    private final Map<String, ContainerSnapshot> containers = new LinkedHashMap<>();
    private final Map<String, List<String>> logs = new LinkedHashMap<>();
    private final Map<String, String> execOutput = new LinkedHashMap<>();
    private final Map<String, ContainerStats> stats = new LinkedHashMap<>();
    private final Set<String> images = new LinkedHashSet<>();

    private final List<String> pulled = new ArrayList<>();
    private final List<String> built = new ArrayList<>();
    private final List<RunSpec> started = new ArrayList<>();
    private final List<String> stopped = new ArrayList<>();
    private final List<String[]> execCalls = new ArrayList<>();

    private DaemonInfo daemon =
            new DaemonInfo("29.4.3", "1.51", "Docker Engine - Community", "linux", true, null);
    private RuntimeException execFailure;
    private int runExitCode;
    private boolean detachedRunsFinishImmediately;

    // ---- construction ----------------------------------------------------------------------

    public ContainerBuilder withContainer(String name) {
        return new ContainerBuilder(name);
    }

    public final class ContainerBuilder {
        private final String name;
        private final List<PortBindingInfo> ports = new ArrayList<>();
        private String state = "exited";
        private String image = "some/image:latest";
        private Instant createdAt = Instant.now().minusSeconds(300);

        private ContainerBuilder(String name) {
            this.name = name;
        }

        public ContainerBuilder running()                { this.state = "running"; return this; }
        public ContainerBuilder exited()                 { this.state = "exited"; return this; }
        public ContainerBuilder image(String image)      { this.image = image; return this; }
        public ContainerBuilder createdAt(Instant when)  { this.createdAt = when; return this; }

        public ContainerBuilder publishing(int hostPort, int containerPort) {
            this.ports.add(new PortBindingInfo(hostPort, containerPort, "tcp"));
            return this;
        }

        public ContainerBuilder publishingUdp(int hostPort, int containerPort) {
            this.ports.add(new PortBindingInfo(hostPort, containerPort, "udp"));
            return this;
        }

        public FakeDockerFacade done() {
            FakeDockerFacade.this.containers.put(this.name, new ContainerSnapshot(
                    "id-" + this.name, this.name, this.image, this.state,
                    this.state.equals("running") ? "Up 5 minutes" : "Exited (0)",
                    this.createdAt, this.ports));
            return FakeDockerFacade.this;
        }
    }

    public FakeDockerFacade withLogs(String container, String... lines) {
        this.logs.put(container, List.of(lines));
        return this;
    }

    public FakeDockerFacade withExecOutput(String container, String output) {
        this.execOutput.put(container, output);
        return this;
    }

    public FakeDockerFacade withExecFailing(RuntimeException failure) {
        this.execFailure = failure;
        return this;
    }

    public FakeDockerFacade withImages(String... references) {
        this.images.addAll(List.of(references));
        return this;
    }

    public FakeDockerFacade withStats(String container, double cpuPercent, long memoryBytes) {
        this.stats.put(container, new ContainerStats(cpuPercent, memoryBytes, 0));
        return this;
    }

    public FakeDockerFacade withDaemon(DaemonInfo daemon) {
        this.daemon = daemon;
        return this;
    }

    public FakeDockerFacade withDaemonUnreachable(String reason) {
        this.daemon = DaemonInfo.unreachable(reason);
        return this;
    }

    public FakeDockerFacade withRunExitCode(int exitCode) {
        this.runExitCode = exitCode;
        return this;
    }

    /**
     * Detached containers land already exited, as if the work had taken no time.
     *
     * <p>What a poll loop needs to complete in one pass: the Spigot builders are polled for
     * "is it still running?", so a fake that always reports "yes" would never let a test finish.
     */
    public FakeDockerFacade withDetachedRunsFinishingImmediately() {
        this.detachedRunsFinishImmediately = true;
        return this;
    }

    public FakeDockerFacade removeContainerNamed(String name) {
        this.containers.remove(name);
        return this;
    }

    // ---- assertions ----------------------------------------------------------------------

    public List<String> pulledImages()   { return List.copyOf(this.pulled); }
    public List<String> builtImages()    { return List.copyOf(this.built); }
    public List<RunSpec> startedSpecs()  { return List.copyOf(this.started); }
    public List<String> stoppedNames()   { return List.copyOf(this.stopped); }
    public List<String[]> execCalls()    { return List.copyOf(this.execCalls); }

    // ---- DockerFacade ----------------------------------------------------------------------

    @Override
    public DaemonInfo daemonInfo() {
        return this.daemon;
    }

    @Override
    public List<ContainerSnapshot> listContainers() {
        return List.copyOf(this.containers.values());
    }

    @Override
    public boolean imageExists(String reference) {
        return this.images.contains(reference);
    }

    @Override
    public void pullImage(String reference, ProgressSink progress) {
        progress.begin("Pulling " + reference + " from docker.io");
        this.pulled.add(reference);
        this.images.add(reference);
        progress.end("pulled");
    }

    @Override
    public void buildImage(String contextPath, String tag, ProgressSink progress) {
        progress.begin("Building image " + tag + " from " + contextPath);
        this.built.add(tag);
        this.images.add(tag);
        progress.end("built");
    }

    @Override
    public List<String> logs(String container, int tail) {
        return this.logs.getOrDefault(container, List.of());
    }

    @Override
    public AutoCloseable followLogs(String container, int tail, Consumer<String> onLine) {
        this.logs(container, tail).forEach(onLine);
        return () -> { };
    }

    @Override
    public Optional<ContainerStats> stats(String container) {
        return Optional.ofNullable(this.stats.get(container));
    }

    @Override
    public String exec(String container, String... argv) {
        this.execCalls.add(argv);
        if (this.execFailure != null) throw this.execFailure;
        return this.execOutput.getOrDefault(container, "");
    }

    @Override
    public void stopContainer(String container, int timeoutSeconds) {
        this.stopped.add(container);
    }

    @Override
    public void removeContainer(String container, boolean force) {
        this.containers.remove(container);
    }

    @Override
    public String runDetached(RunSpec spec) {
        this.started.add(spec);
        if (spec.name() != null) {
            ContainerBuilder container = new ContainerBuilder(spec.name()).image(spec.image());
            if (this.detachedRunsFinishImmediately) container.exited();
            else container.running();
            container.done();
        }
        return "id-" + spec.name();
    }

    @Override
    public int runToCompletion(RunSpec spec, Consumer<String> onLine) {
        this.started.add(spec);
        return this.runExitCode;
    }

    @Override
    public void close() {
    }
}
