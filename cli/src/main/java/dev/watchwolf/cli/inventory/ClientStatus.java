package dev.watchwolf.cli.inventory;

import dev.watchwolf.cli.model.Confidence;

import java.util.Optional;

/**
 * One client bot.
 *
 * <p>Bots are <b>not containers</b> -- they are Python threads inside the single
 * {@code ClientsManager} container -- so a row here is assembled from two sources of unequal
 * quality: a listening socket in the container (authoritative) and a username scraped from the
 * manager's stdout (inferential). {@link #confidence()} carries which, all the way into
 * {@code status --json} and the diagnostics bundle, so a bug report never presents a guess as a
 * fact.
 */
public final class ClientStatus {
    private final int connectorPort;
    private final String username;
    private final String minecraftServer;
    private final Confidence confidence;

    public ClientStatus(int connectorPort, String username, String minecraftServer,
                        Confidence confidence) {
        this.connectorPort = connectorPort;
        this.username = username;
        this.minecraftServer = minecraftServer;
        this.confidence = confidence;
    }

    public int connectorPort()                    { return this.connectorPort; }
    /** The image-stream half of the bot's port pair. */
    public int imagePort()                        { return this.connectorPort + 1; }
    public Optional<String> username()            { return Optional.ofNullable(this.username); }
    public Optional<String> minecraftServer()     { return Optional.ofNullable(this.minecraftServer); }
    public Confidence confidence()                { return this.confidence; }

    /** What the monitor shows: the username when known, else the port. Never a guessed name. */
    public String displayName() {
        return this.username == null ? "bot@" + this.connectorPort : this.username;
    }

    public String portsLabel() {
        return this.connectorPort + "/" + this.imagePort();
    }
}
