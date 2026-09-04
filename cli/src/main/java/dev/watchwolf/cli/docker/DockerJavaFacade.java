package dev.watchwolf.cli.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.Version;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import dev.watchwolf.cli.progress.ProgressSink;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The real {@link DockerFacade}, over docker-java 3.3.6 -- the same client and version
 * WatchWolf-ServersManager already drives the daemon with, so the socket contract is proven.
 *
 * <p><b>One client, closed.</b> The ServersManager constructs a fresh client in five methods and
 * closes none; here there is exactly one, and it is {@link AutoCloseable}.
 */
public final class DockerJavaFacade implements DockerFacade {
    private final DockerClient client;

    public DockerJavaFacade(DockerClient client) {
        this.client = client;
    }

    /** Builds from DOCKER_HOST and the usual defaults -- i.e. the mounted /var/run/docker.sock. */
    public static DockerJavaFacade connect() {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        ApacheDockerHttpClient http = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(20)
                .build();
        return new DockerJavaFacade(DockerClientImpl.getInstance(config, http));
    }

    @Override
    public DaemonInfo daemonInfo() {
        try {
            Version version = this.client.versionCmd().exec();
            String platform = null;
            try {
                Info info = this.client.infoCmd().exec();
                platform = info.getRawValues() == null ? null
                        : String.valueOf(info.getRawValues().getOrDefault("ProductLicense", ""));
                Object rawPlatform = info.getRawValues() == null ? null
                        : info.getRawValues().get("Platform");
                if (rawPlatform instanceof Map<?, ?> platformMap) {
                    Object name = platformMap.get("Name");
                    if (name != null) platform = String.valueOf(name);
                }
            } catch (RuntimeException ignored) {
                // /info is optional for our purposes; /version already proved reachability
            }
            return new DaemonInfo(version.getVersion(), version.getApiVersion(), platform,
                    version.getOperatingSystem(), true, null);
        } catch (RuntimeException ex) {
            return DaemonInfo.unreachable(describe(ex));
        }
    }

    @Override
    public List<ContainerSnapshot> listContainers() {
        List<Container> containers = this.client.listContainersCmd().withShowAll(true).exec();
        List<ContainerSnapshot> snapshots = new ArrayList<>(containers.size());
        for (Container container : containers) {
            snapshots.add(toSnapshot(container));
        }
        return snapshots;
    }

    private static ContainerSnapshot toSnapshot(Container container) {
        List<PortBindingInfo> ports = new ArrayList<>();
        if (container.getPorts() != null) {
            for (ContainerPort port : container.getPorts()) {
                if (port.getPublicPort() == null || port.getPrivatePort() == null) continue;
                ports.add(new PortBindingInfo(port.getPublicPort(), port.getPrivatePort(),
                        port.getType()));
            }
        }
        String name = (container.getNames() == null || container.getNames().length == 0)
                ? container.getId() : container.getNames()[0];
        Instant created = container.getCreated() == null
                ? null : Instant.ofEpochSecond(container.getCreated());
        return new ContainerSnapshot(container.getId(), name, container.getImage(),
                container.getState(), container.getStatus(), created, ports);
    }

    @Override
    public boolean imageExists(String reference) {
        try {
            this.client.inspectImageCmd(reference).exec();
            return true;
        } catch (NotFoundException ex) {
            return false;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @Override
    public void pullImage(String reference, ProgressSink progress) {
        // name the registry, not just the verb: "pulling" is useless when docker.io is what's down
        progress.begin("Pulling " + reference + " from " + registryOf(reference));
        AtomicLong current = new AtomicLong();
        AtomicLong total = new AtomicLong();
        try {
            this.client.pullImageCmd(reference).exec(new PullImageResultCallback() {
                @Override
                public void onNext(PullResponseItem item) {
                    if (item.getProgressDetail() != null) {
                        Long done = item.getProgressDetail().getCurrent();
                        Long all = item.getProgressDetail().getTotal();
                        if (done != null) current.set(done);
                        if (all != null && all > 0) total.set(all);
                        if (total.get() > 0) {
                            progress.update(DockerFacade.ContainerStats.humanBytes(current.get())
                                    + "/" + DockerFacade.ContainerStats.humanBytes(total.get()),
                                    current.get(), total.get());
                        }
                    }
                    super.onNext(item);
                }
            }).awaitCompletion();
            progress.end("pulled");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DockerUnavailableException("Interrupted while pulling " + reference, null, ex);
        } catch (RuntimeException ex) {
            throw new DockerUnavailableException(
                    "Could not pull " + reference + ": " + describe(ex),
                    "Check the daemon has network access to " + registryOf(reference) + ".", ex);
        }
    }

    private static String registryOf(String reference) {
        int slash = reference.indexOf('/');
        if (slash > 0 && reference.substring(0, slash).contains(".")) {
            return reference.substring(0, slash);
        }
        return "docker.io";
    }

    @Override
    public void buildImage(String contextPath, String tag, ProgressSink progress) {
        progress.begin("Building image " + tag + " from " + contextPath);
        try {
            this.client.buildImageCmd(new File(contextPath))
                    .withTags(java.util.Set.of(tag))
                    .exec(new BuildImageResultCallback() {
                        @Override
                        public void onNext(com.github.dockerjava.api.model.BuildResponseItem item) {
                            if (item.getStream() != null && !item.getStream().isBlank()) {
                                progress.detail(item.getStream().strip());
                            }
                            super.onNext(item);
                        }
                    })
                    .awaitImageId();
            progress.end("built");
        } catch (RuntimeException ex) {
            throw new DockerUnavailableException(
                    "Could not build image " + tag + ": " + describe(ex),
                    "Check the Dockerfile in " + contextPath + " and the daemon's build log.", ex);
        }
    }

    @Override
    public List<String> logs(String container, int tail) {
        List<String> lines = new ArrayList<>();
        try {
            this.client.logContainerCmd(container)
                    .withStdOut(true).withStdErr(true).withTail(Math.max(tail, 1))
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            lines.add(new String(frame.getPayload(), StandardCharsets.UTF_8)
                                    .replaceAll("\\R$", ""));
                        }
                    })
                    .awaitCompletion(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (NotFoundException ex) {
            return List.of();
        } catch (RuntimeException ex) {
            return List.of();
        }
        return lines;
    }

    @Override
    public AutoCloseable followLogs(String container, int tail, Consumer<String> onLine) {
        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                onLine.accept(new String(frame.getPayload(), StandardCharsets.UTF_8)
                        .replaceAll("\\R$", ""));
            }
        };
        this.client.logContainerCmd(container)
                .withStdOut(true).withStdErr(true).withFollowStream(true)
                .withTail(Math.max(tail, 1))
                .exec(callback);
        return callback;
    }

    @Override
    public Optional<ContainerStats> stats(String container) {
        try {
            List<Statistics> samples = new ArrayList<>();
            this.client.statsCmd(container).withNoStream(true)
                    .exec(new ResultCallback.Adapter<Statistics>() {
                        @Override
                        public void onNext(Statistics statistics) {
                            samples.add(statistics);
                        }
                    })
                    .awaitCompletion(5, TimeUnit.SECONDS);
            if (samples.isEmpty()) return Optional.empty();
            return toStats(samples.get(0));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (RuntimeException ex) {
            // rootless daemons and cgroup v1 routinely refuse this; the caller renders n/a
            return Optional.empty();
        }
    }

    private static Optional<ContainerStats> toStats(Statistics statistics) {
        try {
            var cpu = statistics.getCpuStats();
            var preCpu = statistics.getPreCpuStats();
            var memory = statistics.getMemoryStats();
            if (cpu == null || preCpu == null || memory == null) return Optional.empty();

            Long total = cpu.getCpuUsage() == null ? null : cpu.getCpuUsage().getTotalUsage();
            Long preTotal = preCpu.getCpuUsage() == null ? null : preCpu.getCpuUsage().getTotalUsage();
            Long system = cpu.getSystemCpuUsage();
            Long preSystem = preCpu.getSystemCpuUsage();

            double percent = 0.0;
            if (total != null && preTotal != null && system != null && preSystem != null) {
                double cpuDelta = total - preTotal;
                double systemDelta = system - preSystem;
                long cpus = cpu.getOnlineCpus() == null ? 1 : cpu.getOnlineCpus();
                if (systemDelta > 0 && cpuDelta >= 0) {
                    percent = (cpuDelta / systemDelta) * cpus * 100.0;
                }
            }
            long used = memory.getUsage() == null ? 0 : memory.getUsage();
            long limit = memory.getLimit() == null ? 0 : memory.getLimit();
            return Optional.of(new ContainerStats(percent, used, limit));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    @Override
    public String exec(String container, String... argv) {
        try {
            String execId = this.client.execCreateCmd(container)
                    .withCmd(argv).withAttachStdout(true).withAttachStderr(true)
                    .exec().getId();

            StringBuilder output = new StringBuilder();
            this.client.execStartCmd(execId).exec(new ResultCallback.Adapter<Frame>() {
                @Override
                public void onNext(Frame frame) {
                    output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                }
            }).awaitCompletion(15, TimeUnit.SECONDS);
            return output.toString();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "";
        } catch (RuntimeException ex) {
            throw new DockerUnavailableException(
                    "Could not run a command inside " + container + ": " + describe(ex),
                    "Is the container running?", ex);
        }
    }

    @Override
    public void stopContainer(String container, int timeoutSeconds) {
        try {
            this.client.stopContainerCmd(container).withTimeout(timeoutSeconds).exec();
        } catch (NotFoundException ex) {
            // already gone -- that is the state we wanted
        }
    }

    @Override
    public void removeContainer(String container, boolean force) {
        try {
            this.client.removeContainerCmd(container).withForce(force).exec();
        } catch (NotFoundException ex) {
            // already gone
        }
    }

    @Override
    public String runDetached(RunSpec spec) {
        CreateContainerResponse created = this.create(spec);
        this.client.startContainerCmd(created.getId()).exec();
        return created.getId();
    }

    @Override
    public int runToCompletion(RunSpec spec, Consumer<String> onLine) {
        CreateContainerResponse created = this.create(spec.autoRemove(false));
        this.client.startContainerCmd(created.getId()).exec();
        try {
            if (onLine != null) {
                this.client.logContainerCmd(created.getId())
                        .withStdOut(true).withStdErr(true).withFollowStream(true)
                        .exec(new ResultCallback.Adapter<Frame>() {
                            @Override
                            public void onNext(Frame frame) {
                                onLine.accept(new String(frame.getPayload(), StandardCharsets.UTF_8)
                                        .replaceAll("\\R$", ""));
                            }
                        });
            }
            Integer status = this.client.waitContainerCmd(created.getId())
                    .exec(new WaitContainerResultCallback()).awaitStatusCode();
            return status == null ? -1 : status;
        } finally {
            this.removeContainer(created.getId(), true);
        }
    }

    private CreateContainerResponse create(RunSpec spec) {
        CreateContainerCmd command = this.client.createContainerCmd(spec.image());
        if (spec.name() != null) command.withName(spec.name());
        if (!spec.entrypoint().isEmpty()) command.withEntrypoint(spec.entrypoint());
        if (!spec.command().isEmpty()) command.withCmd(spec.command());
        if (spec.workingDir() != null) command.withWorkingDir(spec.workingDir());
        if (spec.user() != null) command.withUser(spec.user());
        if (!spec.environment().isEmpty()) {
            List<String> env = new ArrayList<>();
            spec.environment().forEach((key, value) -> env.add(key + "=" + value));
            command.withEnv(env);
        }

        HostConfig hostConfig = new HostConfig().withAutoRemove(spec.autoRemove());
        if (!spec.binds().isEmpty()) {
            List<Bind> binds = new ArrayList<>();
            spec.binds().forEach((host, inside) -> binds.add(Bind.parse(host + ":" + inside)));
            hostConfig.withBinds(binds);
        }
        if (spec.hostNetwork()) hostConfig.withNetworkMode("host");
        command.withHostConfig(hostConfig);

        return command.exec();
    }

    @Override
    public void close() {
        try {
            this.client.close();
        } catch (Exception ignored) {
            // closing a client we are discarding anyway
        }
    }

    private static String describe(Throwable ex) {
        if (ex instanceof DockerException dockerException && dockerException.getHttpStatus() > 0) {
            return dockerException.getMessage() + " (HTTP " + dockerException.getHttpStatus() + ")";
        }
        String message = ex.getMessage();
        return (message == null || message.isBlank()) ? ex.getClass().getSimpleName() : message;
    }
}
