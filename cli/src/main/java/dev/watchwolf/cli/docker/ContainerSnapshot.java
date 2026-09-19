package dev.watchwolf.cli.docker;

import dev.watchwolf.cli.parse.ContainerNames;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** What Docker knows about one container, normalised. */
public final class ContainerSnapshot {
    private final String id;
    private final String name;
    private final String image;
    private final String state;
    private final String status;
    private final Instant createdAt;
    private final List<PortBindingInfo> ports;

    public ContainerSnapshot(String id, String name, String image, String state, String status,
                             Instant createdAt, List<PortBindingInfo> ports) {
        this.id = id;
        // Docker reports names with a leading slash; normalising here means no caller has to
        // remember (the ServersManager forgets, which is why its own cleanup never matches).
        this.name = ContainerNames.normalise(name);
        this.image = image;
        this.state = state;
        this.status = status;
        this.createdAt = createdAt;
        this.ports = List.copyOf(ports == null ? List.of() : ports);
    }

    public String id()                    { return this.id; }
    public String name()                  { return this.name; }
    public String image()                 { return this.image; }
    public String state()                 { return this.state; }
    public String status()                { return this.status; }
    public Instant createdAt()            { return this.createdAt; }
    public List<PortBindingInfo> ports()  { return this.ports; }

    public boolean isRunning() {
        return "running".equalsIgnoreCase(this.state);
    }

    public Optional<Duration> uptime(Instant now) {
        if (this.createdAt == null || !this.isRunning()) return Optional.empty();
        return Optional.of(Duration.between(this.createdAt, now));
    }

    /** Published TCP host ports, ascending. UDP is dropped: MC publishes 25565 on both. */
    public List<Integer> publishedTcpPorts() {
        List<Integer> hostPorts = new ArrayList<>();
        for (PortBindingInfo binding : this.ports) {
            if (binding.isTcp() && binding.hostPort() > 0 && !hostPorts.contains(binding.hostPort())) {
                hostPorts.add(binding.hostPort());
            }
        }
        hostPorts.sort(Integer::compareTo);
        return hostPorts;
    }

    /**
     * The {@code (minecraft, watchwolf)} pair of an MC server container.
     *
     * <p>{@code DockerizedServerInstantiator} publishes {@code p -> 25565} (tcp and udp) and
     * {@code p+1 -> 25566}, always consecutive. Read from the container rather than recomputed,
     * so a server started by an older manager still reports correctly.
     */
    public Optional<int[]> minecraftAndWatchWolfPorts() {
        Integer minecraft = null;
        Integer watchWolf = null;
        for (PortBindingInfo binding : this.ports) {
            if (!binding.isTcp()) continue;
            if (binding.containerPort() == 25565) minecraft = binding.hostPort();
            if (binding.containerPort() == 25566) watchWolf = binding.hostPort();
        }
        if (minecraft == null || watchWolf == null) return Optional.empty();
        return Optional.of(new int[] { minecraft, watchWolf });
    }

    @Override
    public String toString() {
        return this.name + "[" + this.state + " " + this.ports + "]";
    }
}
