package dev.watchwolf.cli.parse;

import dev.watchwolf.cli.model.ClientLogEvent;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Folds a ClientsManager log into "which bot is on which port, and which server did it join".
 *
 * <p><b>The adjacency rule.</b> A bot's assigned port appears only in {@code Client started at
 * <ip>:<port>}, which does not name the bot; the name is on the preceding {@code Starting client
 * <user> ...} line. But {@code ClientsManagerConnector._client_manager} runs one thread per Tester
 * connection, so with two Testers starting bots at once those lines interleave and naive pairing
 * invents wrong answers.
 *
 * <p>So a port is attributed <b>only</b> when {@code Client started} immediately follows a
 * {@code Starting client} with nothing else recognised in between. Otherwise the port is recorded
 * with no name and the monitor shows it as {@code bot@7005}. An unnamed row is honest; a wrongly
 * named one is a lie that reaches a bug report.
 */
public final class ClientsManagerLogReader {
    private final Map<Integer, String> usernameByPort = new LinkedHashMap<>();
    private final Set<Integer> unattributedPorts = new LinkedHashSet<>();
    private final Map<String, String> minecraftServerByUsername = new LinkedHashMap<>();
    private final Set<String> usernamesSeen = new LinkedHashSet<>();

    /** Set only while the immediately previous recognised event was CLIENT_STARTING. */
    private String pendingUsername;

    public void accept(String line) {
        ClientLogEvent event = ClientsManagerLogParser.parseLine(line);
        if (event == null) return;                      // unrecognised lines do not break adjacency

        switch (event.kind()) {
            case CLIENT_STARTING -> {
                event.username().ifPresent(this.usernamesSeen::add);
                // A second "Starting client" before any "Client started" means the two are racing;
                // the newest one is the only candidate we could still pair, but we cannot know
                // that, so drop the attribution entirely.
                this.pendingUsername = (this.pendingUsername == null)
                        ? event.username().orElse(null)
                        : null;
            }
            case CLIENT_STARTED -> {
                int port = event.port().orElse(-1);
                if (port > 0) {
                    if (this.pendingUsername != null) {
                        this.usernameByPort.put(port, this.pendingUsername);
                        this.unattributedPorts.remove(port);
                    } else if (!this.usernameByPort.containsKey(port)) {
                        this.unattributedPorts.add(port);
                    }
                }
                this.pendingUsername = null;
            }
            case CLIENT_MESSAGE -> {
                event.username().ifPresent(user -> {
                    this.usernamesSeen.add(user);
                    // the prefix's host:port is the MINECRAFT server, which usefully joins this
                    // bot to a row in the servers panel
                    event.minecraftServerAddress()
                         .ifPresent(address -> this.minecraftServerByUsername.put(user, address));
                });
                this.pendingUsername = null;
            }
            case CLIENT_DISCONNECTED -> this.pendingUsername = null;
        }
    }

    public void acceptAll(Iterable<String> lines) {
        for (String line : lines) this.accept(line);
    }

    public static ClientsManagerLogReader over(Iterable<String> lines) {
        ClientsManagerLogReader reader = new ClientsManagerLogReader();
        reader.acceptAll(lines);
        return reader;
    }

    public Optional<String> usernameForPort(int port) {
        return Optional.ofNullable(this.usernameByPort.get(port));
    }

    public Map<Integer, String> usernameByPort() {
        return Map.copyOf(this.usernameByPort);
    }

    /** Ports seen starting whose username could not be established unambiguously. */
    public Set<Integer> unattributedPorts() {
        return Set.copyOf(this.unattributedPorts);
    }

    public Optional<String> minecraftServerFor(String username) {
        return Optional.ofNullable(this.minecraftServerByUsername.get(username));
    }

    /** Every username the log mentions -- including ones no longer listening. */
    public Set<String> usernamesSeen() {
        return Set.copyOf(this.usernamesSeen);
    }
}
