package dev.watchwolf.cli.inventory;

import dev.watchwolf.cli.docker.ContainerSnapshot;
import dev.watchwolf.cli.docker.DockerFacade;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** A manager (ServersManager or ClientsManager): is it up, on which ports, how busy. */
public final class ManagerStatus {
    public enum Kind {
        SERVERS_MANAGER("ServersManager", 8000, 8000),
        CLIENTS_MANAGER("ClientsManager", 7000, 7199);

        private final String containerName;
        private final int port;
        private final int portRangeEnd;

        Kind(String containerName, int port, int portRangeEnd) {
            this.containerName = containerName;
            this.port = port;
            this.portRangeEnd = portRangeEnd;
        }

        public String containerName() { return this.containerName; }
        public int port()             { return this.port; }
        public int portRangeEnd()     { return this.portRangeEnd; }

        public String portLabel() {
            return this.port == this.portRangeEnd
                    ? String.valueOf(this.port)
                    : this.port + "-" + this.portRangeEnd;
        }
    }

    private final Kind kind;
    private final ContainerSnapshot container;
    private final boolean accepting;
    private final DockerFacade.ContainerStats stats;

    public ManagerStatus(Kind kind, ContainerSnapshot container, boolean accepting,
                         DockerFacade.ContainerStats stats) {
        this.kind = kind;
        this.container = container;
        this.accepting = accepting;
        this.stats = stats;
    }

    public Kind kind()                                  { return this.kind; }
    public String name()                                { return this.kind.containerName(); }
    public Optional<ContainerSnapshot> container()      { return Optional.ofNullable(this.container); }
    public Optional<DockerFacade.ContainerStats> stats() { return Optional.ofNullable(this.stats); }

    /** The container exists and is running. */
    public boolean isUp() {
        return this.container != null && this.container.isRunning();
    }

    /**
     * The socket actually answered.
     *
     * <p>Distinct from {@link #isUp()} on purpose: a container that is running but not yet
     * listening is the state every "Connection refused" bug report starts from.
     */
    public boolean isAccepting() {
        return this.accepting;
    }

    public String stateLabel() {
        if (!this.isUp()) return "offline";
        return this.accepting ? "online" : "starting";
    }

    public Optional<Duration> uptime(Instant now) {
        return this.container().flatMap(c -> c.uptime(now));
    }

    public Optional<String> image() {
        return this.container().map(ContainerSnapshot::image);
    }

    /** The dot the dashboard paints beside the name: filled when the socket actually answered. */
    public String confidenceGlyph() {
        if (this.accepting) return "\u25cf";      // filled: running AND listening
        if (this.isUp()) return "\u25d0";         // half: up, but nothing answers yet
        return "\u25cb";                          // hollow: not running
    }
}
