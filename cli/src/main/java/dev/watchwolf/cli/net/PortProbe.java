package dev.watchwolf.cli.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * TCP reachability, used by {@code doctor} for the managers' ports 8000 and 7000.
 *
 * <p>Deliberately <b>not</b> used to discover client bots. The ClientsManager publishes
 * {@code 7000-7199}, so docker-proxy accepts on all 200 host ports whether or not a bot exists
 * behind them; every probe would succeed. Bot discovery reads the container's own
 * {@code /proc/net/tcp} instead.
 */
public class PortProbe {
    private final int timeoutMillis;

    public PortProbe() {
        this(2000);
    }

    public PortProbe(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public boolean isAccepting(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), this.timeoutMillis);
            return true;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }
}
