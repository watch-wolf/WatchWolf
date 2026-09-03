package dev.watchwolf.cli.inventory;

import dev.watchwolf.cli.docker.ContainerSnapshot;
import dev.watchwolf.cli.docker.DockerFacade;
import dev.watchwolf.cli.model.Confidence;
import dev.watchwolf.cli.model.SessionInfo;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * One Minecraft server: an {@code MC_Server-<millis>} container, joined to its
 * {@code logs/<millis>/info.txt} by the shared id.
 */
public final class McServerStatus {
    private final String sessionId;
    private final ContainerSnapshot container;
    private final SessionInfo info;
    private final boolean tmpFolderPresent;
    private final boolean logsReadable;
    private final DockerFacade.ContainerStats stats;

    public McServerStatus(String sessionId, ContainerSnapshot container, SessionInfo info,
                          boolean tmpFolderPresent, boolean logsReadable,
                          DockerFacade.ContainerStats stats) {
        this.sessionId = sessionId;
        this.container = container;
        this.info = info;
        this.tmpFolderPresent = tmpFolderPresent;
        this.logsReadable = logsReadable;
        this.stats = stats;
    }

    public String sessionId()                            { return this.sessionId; }
    public Optional<ContainerSnapshot> container()       { return Optional.ofNullable(this.container); }
    public Optional<SessionInfo> info()                  { return Optional.ofNullable(this.info); }
    public boolean tmpFolderPresent()                    { return this.tmpFolderPresent; }
    public boolean logsReadable()                        { return this.logsReadable; }
    public Optional<DockerFacade.ContainerStats> stats() { return Optional.ofNullable(this.stats); }

    public String name() {
        return this.container == null ? "MC_Server-" + this.sessionId : this.container.name();
    }

    public boolean isRunning() {
        return this.container != null && this.container.isRunning();
    }

    public String type() {
        return this.info == null ? "?" : this.info.serverType().orElse("?");
    }

    public String version() {
        return this.info == null ? "?" : this.info.serverVersion().orElse("?");
    }

    /** {@code <minecraftPort>/<watchWolfPort>}, read from the container's published bindings. */
    public Optional<int[]> ports() {
        return this.container().flatMap(ContainerSnapshot::minecraftAndWatchWolfPorts);
    }

    public String portsLabel() {
        return this.ports().map(pair -> pair[0] + "/" + pair[1]).orElse("-");
    }

    public Optional<Duration> uptime(Instant now) {
        return this.container().flatMap(c -> c.uptime(now));
    }

    /**
     * A running container with no {@code info.txt} is {@code INFERRED}, not hidden.
     *
     * <p>That combination is exactly what an orphaned server looks like -- the case where the
     * ServersManager lost track of a container and the next run fails immediately. Hiding it would
     * hide the bug.
     */
    public Confidence confidence() {
        if (this.container == null) return Confidence.INFERRED;   // logs only, container gone
        return this.info == null ? Confidence.INFERRED : Confidence.OBSERVED;
    }

    /** No container but a logs folder: the run is over, and only its record survives. */
    public boolean isHistoricalOnly() {
        return this.container == null;
    }
}
