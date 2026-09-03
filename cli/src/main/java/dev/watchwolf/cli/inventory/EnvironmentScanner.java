package dev.watchwolf.cli.inventory;

import dev.watchwolf.cli.docker.ContainerSnapshot;
import dev.watchwolf.cli.docker.DaemonInfo;
import dev.watchwolf.cli.docker.DockerFacade;
import dev.watchwolf.cli.io.FileGateway;
import dev.watchwolf.cli.layout.InstallLayout;
import dev.watchwolf.cli.model.SessionInfo;
import dev.watchwolf.cli.net.HostInterfaces;
import dev.watchwolf.cli.net.PortProbe;
import dev.watchwolf.cli.parse.ContainerNames;
import dev.watchwolf.cli.parse.InfoTxtParser;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds an {@link EnvironmentSnapshot} from Docker plus the install directory.
 *
 * <p>Everything it touches arrives through a seam, so the whole thing is unit-testable against a
 * fake daemon and a temp directory.
 */
public final class EnvironmentScanner {
    private final DockerFacade docker;
    private final FileGateway files;
    private final InstallLayout layout;
    private final ClientDiscovery clientDiscovery;
    private final PortProbe portProbe;
    private final HostInterfaces interfaces;
    private final Clock clock;

    private boolean collectStats = true;

    public EnvironmentScanner(DockerFacade docker, FileGateway files, InstallLayout layout,
                              ClientDiscovery clientDiscovery, PortProbe portProbe,
                              HostInterfaces interfaces, Clock clock) {
        this.docker = docker;
        this.files = files;
        this.layout = layout;
        this.clientDiscovery = clientDiscovery;
        this.portProbe = portProbe;
        this.interfaces = interfaces;
        this.clock = clock;
    }

    /** Stats are the slowest part of a poll; the monitor turns them off while scrolling. */
    public EnvironmentScanner collectingStats(boolean collectStats) {
        this.collectStats = collectStats;
        return this;
    }

    public EnvironmentSnapshot scan() {
        DaemonInfo daemon = this.docker.daemonInfo();
        if (!daemon.reachable()) {
            return new EnvironmentSnapshot(this.clock.instant(), false, null,
                    daemon.unreachableReason(), false,
                    new ManagerStatus(ManagerStatus.Kind.SERVERS_MANAGER, null, false, null),
                    new ManagerStatus(ManagerStatus.Kind.CLIENTS_MANAGER, null, false, null),
                    List.of(), ClientDiscovery.Result.unavailable("Docker is unreachable."), null);
        }

        Map<String, ContainerSnapshot> byName = new LinkedHashMap<>();
        for (ContainerSnapshot container : this.docker.listContainers()) {
            byName.put(container.name(), container);
        }

        ManagerStatus serversManager =
                this.manager(ManagerStatus.Kind.SERVERS_MANAGER, byName);
        ManagerStatus clientsManager =
                this.manager(ManagerStatus.Kind.CLIENTS_MANAGER, byName);

        List<McServerStatus> servers = this.servers(byName);
        ClientDiscovery.Result clients = this.clientDiscovery.discover(clientsManager.isUp());

        return new EnvironmentSnapshot(this.clock.instant(), true, daemon.serverVersion(), null,
                daemon.hostNetworkingIsTruthful(), serversManager, clientsManager, servers, clients,
                this.interfaces.preferredMachineIp());
    }

    private ManagerStatus manager(ManagerStatus.Kind kind, Map<String, ContainerSnapshot> byName) {
        ContainerSnapshot container = byName.get(kind.containerName());
        boolean running = container != null && container.isRunning();
        // "running" and "listening" are different states, and the gap between them is where every
        // Connection refused report begins
        boolean accepting = running && this.portProbe.isAccepting("127.0.0.1", kind.port());
        return new ManagerStatus(kind, container, accepting, running ? this.statsFor(container) : null);
    }

    private List<McServerStatus> servers(Map<String, ContainerSnapshot> byName) {
        Map<String, McServerStatus> bySession = new LinkedHashMap<>();

        // 1. live containers
        for (ContainerSnapshot container : byName.values()) {
            Optional<String> sessionId = ContainerNames.mcServerSessionId(container.name());
            if (sessionId.isEmpty()) continue;
            String id = sessionId.get();
            bySession.put(id, new McServerStatus(id, container, this.readSessionInfo(id),
                    this.files.isDirectory(this.layout.tmp(id)),
                    this.files.isReadable(this.layout.sessionLogFile(id)),
                    this.statsFor(container)));
        }

        // 2. finished runs that only left a logs/<id>/ behind. Those containers ran with
        //    --autoRemove, so Docker has nothing on them, but the log survives -- which is
        //    precisely why the monitor tails the file and not `docker logs`.
        for (Path entry : this.files.list(this.layout.logs())) {
            String id = entry.getFileName().toString();
            if (bySession.containsKey(id) || !id.matches("\\d+")) continue;
            bySession.put(id, new McServerStatus(id, null, this.readSessionInfo(id),
                    this.files.isDirectory(this.layout.tmp(id)),
                    this.files.isReadable(this.layout.sessionLogFile(id)), null));
        }

        List<McServerStatus> servers = new ArrayList<>(bySession.values());
        // running first, then most recent session id
        servers.sort(Comparator.comparing(McServerStatus::isRunning).reversed()
                .thenComparing(McServerStatus::sessionId, Comparator.reverseOrder()));
        return servers;
    }

    private SessionInfo readSessionInfo(String sessionId) {
        Path infoFile = this.layout.sessionInfoFile(sessionId);
        if (!this.files.isReadable(infoFile)) return null;   // often root-owned; not an error
        try {
            return InfoTxtParser.parse(sessionId, this.files.readString(infoFile));
        } catch (IOException ex) {
            return null;
        }
    }

    private DockerFacade.ContainerStats statsFor(ContainerSnapshot container) {
        if (!this.collectStats || container == null || !container.isRunning()) return null;
        return this.docker.stats(container.name()).orElse(null);
    }
}
