package dev.watchwolf.cli.parse;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reads listening TCP ports out of a container's {@code /proc/net/tcp} (and {@code tcp6}).
 *
 * <p>This is how the monitor establishes which client bots actually exist. It is exact, because
 * {@code ClientsManager.get_min_id()} hands out 7001, 7003, 7005 ... and each bot's
 * {@code ClientConnector} binds its assigned port.
 *
 * <p><b>Do not "simplify" this into a TCP connect from the host.</b> The ClientsManager is published
 * with {@code -p 7000-7199:7000-7199}, so docker-proxy listens on all 200 host ports whether or not
 * a bot exists behind them; every probe would succeed and every port would look occupied. Reading
 * the container's own socket table is the only way to tell.
 *
 * <p>Format (one header line, then whitespace-separated columns):
 * <pre>
 *   sl  local_address rem_address   st tx_queue:rx_queue ...
 *    0: 00000000:1B5B 00000000:0000 0A 00000000:00000000 ...
 * </pre>
 * {@code local_address} is {@code <hex address>:<hex port>}; state {@code 0A} is LISTEN.
 */
public final class ProcNetTcpParser {
    /** TCP_LISTEN, from the kernel's tcp_states enum. */
    private static final String STATE_LISTEN = "0A";

    private ProcNetTcpParser() {
    }

    /** Every port in LISTEN state, in file order. Accepts tcp and tcp6 contents concatenated. */
    public static Set<Integer> listeningPorts(String procNetTcpContents) {
        Set<Integer> ports = new LinkedHashSet<>();
        if (procNetTcpContents == null) return ports;

        for (String line : procNetTcpContents.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;

            String[] columns = trimmed.split("\\s+");
            // sl, local_address, rem_address, st -- anything shorter is the header or garbage
            if (columns.length < 4) continue;
            if (!STATE_LISTEN.equalsIgnoreCase(columns[3])) continue;

            int separator = columns[1].lastIndexOf(':');
            if (separator < 0) continue;

            try {
                ports.add(Integer.parseInt(columns[1].substring(separator + 1), 16));
            } catch (NumberFormatException ignored) {
                // header row ("local_address") or a malformed line; not our problem
            }
        }
        return ports;
    }

    /**
     * The subset that are client-bot connector ports.
     *
     * <p>Bots get a consecutive pair starting at {@code managerPort + 1}, stepping by 2: the odd
     * port is the connector, the even one streams recorded images. Only the odd ones identify a bot.
     */
    public static Set<Integer> clientConnectorPorts(String procNetTcpContents,
                                                    int managerPort, int rangeEnd) {
        int firstClientPort = managerPort + 1;
        Set<Integer> ports = new LinkedHashSet<>();
        for (int port : listeningPorts(procNetTcpContents)) {
            if (port < firstClientPort || port > rangeEnd) continue;
            if (((port - firstClientPort) % 2) != 0) continue;   // even offset == connector port
            ports.add(port);
        }
        return ports;
    }
}
