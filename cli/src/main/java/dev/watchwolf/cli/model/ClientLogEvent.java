package dev.watchwolf.cli.model;

import java.util.Objects;
import java.util.Optional;

/**
 * One meaningful line from the ClientsManager's stdout.
 *
 * <p>Three shapes matter, and they carry different things -- conflating them is the easy mistake:
 *
 * <ul>
 *   <li>{@link Kind#CLIENT_MESSAGE} -- {@code [<username> - <host>:<port>] <msg>}. The
 *       {@code host:port} here is the <b>Minecraft server the bot joined</b>, NOT the bot's own
 *       700x port. It is still useful: it joins a bot to a row in the servers panel.</li>
 *   <li>{@link Kind#CLIENT_STARTING} -- {@code Starting client <user> at server <addr>...}</li>
 *   <li>{@link Kind#CLIENT_STARTED} -- {@code Client started at <ip>:<port>}. This is the
 *       <b>only</b> source of a bot's assigned port, and it does not name the user; the username
 *       has to come from the immediately preceding CLIENT_STARTING line.</li>
 * </ul>
 */
public final class ClientLogEvent {
    public enum Kind { CLIENT_MESSAGE, CLIENT_STARTING, CLIENT_STARTED, CLIENT_DISCONNECTED }

    private final Kind kind;
    private final String username;
    private final String host;
    private final Integer port;
    private final String message;
    private final String rawLine;

    public ClientLogEvent(Kind kind, String username, String host, Integer port,
                          String message, String rawLine) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.username = username;
        this.host = host;
        this.port = port;
        this.message = message;
        this.rawLine = rawLine;
    }

    public Kind kind()                     { return this.kind; }
    public Optional<String> username()     { return Optional.ofNullable(this.username); }
    public Optional<String> host()          { return Optional.ofNullable(this.host); }
    public Optional<Integer> port()         { return Optional.ofNullable(this.port); }
    public Optional<String> message()       { return Optional.ofNullable(this.message); }
    public String rawLine()                 { return this.rawLine; }

    /**
     * For {@link Kind#CLIENT_MESSAGE}, the Minecraft server address the bot is on -- deliberately
     * named so nobody mistakes it for the bot's own port.
     */
    public Optional<String> minecraftServerAddress() {
        if (this.kind != Kind.CLIENT_MESSAGE || this.host == null || this.port == null) {
            return Optional.empty();
        }
        return Optional.of(this.host + ":" + this.port);
    }

    @Override
    public String toString() {
        return this.kind + "[" + this.username + " " + this.host + ":" + this.port + "]";
    }
}
