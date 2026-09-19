package dev.watchwolf.cli.inventory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * An immutable picture of the whole environment at one instant.
 *
 * <p>The monitor's poller publishes these through an {@code AtomicReference} and the draw loop only
 * reads them, so rendering never blocks on Docker. {@code status} prints exactly the same object.
 */
public final class EnvironmentSnapshot {
    private final Instant takenAt;
    private final boolean dockerReachable;
    private final String dockerVersion;
    private final String dockerUnreachableReason;
    private final boolean hostNetworkingTruthful;
    private final ManagerStatus serversManager;
    private final ManagerStatus clientsManager;
    private final List<McServerStatus> servers;
    private final ClientDiscovery.Result clients;
    private final String advertisedAddress;

    public EnvironmentSnapshot(Instant takenAt, boolean dockerReachable, String dockerVersion,
                               String dockerUnreachableReason, boolean hostNetworkingTruthful,
                               ManagerStatus serversManager, ManagerStatus clientsManager,
                               List<McServerStatus> servers, ClientDiscovery.Result clients,
                               String advertisedAddress) {
        this.takenAt = takenAt;
        this.dockerReachable = dockerReachable;
        this.dockerVersion = dockerVersion;
        this.dockerUnreachableReason = dockerUnreachableReason;
        this.hostNetworkingTruthful = hostNetworkingTruthful;
        this.serversManager = serversManager;
        this.clientsManager = clientsManager;
        this.servers = List.copyOf(servers);
        this.clients = clients;
        this.advertisedAddress = advertisedAddress;
    }

    public Instant takenAt()                       { return this.takenAt; }
    public boolean dockerReachable()               { return this.dockerReachable; }
    public String dockerVersion()                  { return this.dockerVersion; }
    public boolean hostNetworkingTruthful()        { return this.hostNetworkingTruthful; }
    public ManagerStatus serversManager()          { return this.serversManager; }
    public ManagerStatus clientsManager()          { return this.clientsManager; }
    public List<McServerStatus> servers()          { return this.servers; }
    public ClientDiscovery.Result clients()        { return this.clients; }
    public String advertisedAddress()              { return this.advertisedAddress; }

    public Optional<String> dockerUnreachableReason() {
        return Optional.ofNullable(this.dockerUnreachableReason);
    }

    public List<ManagerStatus> managers() {
        return List.of(this.serversManager, this.clientsManager);
    }

    public List<McServerStatus> runningServers() {
        return this.servers.stream().filter(McServerStatus::isRunning).toList();
    }

    public boolean anythingRunning() {
        return this.serversManager.isUp() || this.clientsManager.isUp()
                || !this.runningServers().isEmpty();
    }

    public Optional<McServerStatus> serverById(String sessionId) {
        return this.servers.stream().filter(s -> s.sessionId().equals(sessionId)).findFirst();
    }

    /** The MC server a bot joined, matched by the address in its log prefix. */
    public Optional<McServerStatus> serverForClient(ClientStatus client) {
        String address = client.minecraftServer().orElse(null);
        if (address == null) return Optional.empty();

        int colon = address.lastIndexOf(':');
        if (colon < 0) return Optional.empty();
        try {
            int port = Integer.parseInt(address.substring(colon + 1));
            return this.servers.stream()
                    .filter(server -> server.ports().map(pair -> pair[0] == port).orElse(false))
                    .findFirst();
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }
}
