package dev.watchwolf.cli.inventory;

import dev.watchwolf.cli.docker.DockerFacade;
import dev.watchwolf.cli.model.Confidence;
import dev.watchwolf.cli.parse.ClientsManagerLogReader;
import dev.watchwolf.cli.parse.ContainerNames;
import dev.watchwolf.cli.parse.ProcNetTcpParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Finds client bots by combining two signals of unequal quality.
 *
 * <ol>
 *   <li><b>Liveness -- authoritative.</b> {@code /proc/net/tcp} inside the ClientsManager
 *       container, filtered to listening odd ports in the client range. Exact, because
 *       {@code get_min_id()} hands out 7001, 7003, ... and each bot's connector binds one.
 *       <p><b>Do not replace this with a TCP connect from the host.</b> The manager is published
 *       as {@code -p 7000-7199:7000-7199}, so docker-proxy accepts on all 200 host ports whether
 *       or not a bot exists; every probe would succeed and every port would look occupied.</li>
 *   <li><b>Identity -- inferential.</b> The manager's stdout, read by
 *       {@link ClientsManagerLogReader}, which pairs a username to a port only when the two log
 *       lines are unambiguously adjacent.</li>
 * </ol>
 *
 * <p><b>The merge rule: the listening-port set is the truth, names are decoration.</b> A row is
 * never synthesised from the log alone, and a port whose name could not be established shows as
 * {@code bot@7005} rather than borrowing a plausible one.
 *
 * <p>The real fix is a "list clients" operation in the protocol. That is a wire-format change and
 * is deliberately out of scope here; {@link ClientDiscovery.Result#limitation()} says so rather
 * than pretending the inference is exact.
 */
public final class SocketAndLogClientDiscovery implements ClientDiscovery {
    private static final int LOG_LINES_TO_READ = 2000;

    private final DockerFacade docker;
    private final int managerPort;
    private final int rangeEnd;

    public SocketAndLogClientDiscovery(DockerFacade docker, int managerPort, int rangeEnd) {
        this.docker = docker;
        this.managerPort = managerPort;
        this.rangeEnd = rangeEnd;
    }

    public SocketAndLogClientDiscovery(DockerFacade docker) {
        this(docker, ManagerStatus.Kind.CLIENTS_MANAGER.port(),
                ManagerStatus.Kind.CLIENTS_MANAGER.portRangeEnd());
    }

    @Override
    public Result discover(boolean managerRunning) {
        if (!managerRunning) {
            return Result.unavailable("The ClientsManager is not running, so no bots can exist.");
        }

        ClientsManagerLogReader log = this.readLog();
        Set<Integer> listening = this.readListeningPorts();

        if (listening == null) {
            // exec refused, or the container has no readable /proc: fall back to the log alone and
            // mark every row inferred, rather than silently showing stale bots as live ones
            return this.fromLogOnly(log);
        }

        List<ClientStatus> clients = new ArrayList<>();
        for (int port : listening) {
            String username = log.usernameForPort(port).orElse(null);
            String server = username == null ? null : log.minecraftServerFor(username).orElse(null);
            clients.add(new ClientStatus(port, username, server,
                    username == null ? Confidence.INFERRED : Confidence.OBSERVED));
        }

        String limitation = null;
        long namedButNotListening = log.usernamesSeen().size()
                - clients.stream().filter(c -> c.username().isPresent()).count();
        if (namedButNotListening > 0) {
            limitation = namedButNotListening + " bot(s) named in the log are no longer listening.";
        }
        return new Result(clients, "clients (" + clients.size() + ")", limitation);
    }

    private Result fromLogOnly(ClientsManagerLogReader log) {
        List<ClientStatus> clients = new ArrayList<>();
        log.usernameByPort().forEach((port, username) -> clients.add(new ClientStatus(
                port, username, log.minecraftServerFor(username).orElse(null),
                Confidence.INFERRED)));
        for (int port : log.unattributedPorts()) {
            clients.add(new ClientStatus(port, null, null, Confidence.INFERRED));
        }
        return new Result(clients, "clients (from log -- may be stale)",
                "Could not read the container's socket table, so these come from the log alone "
                        + "and may include bots that have already stopped.");
    }

    /** {@code null} means we could not read it -- distinct from "read it, found nothing". */
    private Set<Integer> readListeningPorts() {
        try {
            String tables = this.docker.exec(ContainerNames.CLIENTS_MANAGER,
                    "cat", "/proc/net/tcp", "/proc/net/tcp6");
            if (tables == null || tables.isBlank()) return null;
            return ProcNetTcpParser.clientConnectorPorts(tables, this.managerPort, this.rangeEnd);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private ClientsManagerLogReader readLog() {
        try {
            return ClientsManagerLogReader.over(
                    this.docker.logs(ContainerNames.CLIENTS_MANAGER, LOG_LINES_TO_READ));
        } catch (RuntimeException ex) {
            return new ClientsManagerLogReader();   // empty: ports without names
        }
    }
}
