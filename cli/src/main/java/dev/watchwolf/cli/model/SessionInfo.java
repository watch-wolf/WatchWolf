package dev.watchwolf.cli.model;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What the ServersManager recorded about one server run, from {@code logs/<millis>/info.txt}.
 *
 * <p>The id is the {@code System.currentTimeMillis()} the ServersManager used for the server's
 * scratch folder, which makes it the join key across three places: the {@code MC_Server-<id>}
 * container name, {@code tmp/<id>/} and {@code logs/<id>/}.
 */
public final class SessionInfo {
    private final String id;
    private final Map<String, String> fields;

    public SessionInfo(String id, Map<String, String> fields) {
        this.id = Objects.requireNonNull(id, "id");
        this.fields = Map.copyOf(fields);
    }

    public String id() { return this.id; }

    public Map<String, String> fields() { return this.fields; }

    public Optional<String> field(String key) {
        return Optional.ofNullable(this.fields.get(key));
    }

    public Optional<String> serverType()    { return this.field("serverType"); }
    public Optional<String> serverVersion() { return this.field("serverVersion"); }
    public Optional<String> createdAt()     { return this.field("createdAt"); }

    /** The address the ServersManager advertised for this server, as {@code <ip>:<port>}. */
    public Optional<String> advertisedIp()  { return this.field("ip"); }

    public Optional<ServerTypeVersion> typeAndVersion() {
        String type = this.fields.get("serverType");
        McVersion version = McVersion.parseOrNull(this.fields.get("serverVersion"));
        if (type == null || version == null) return Optional.empty();
        return Optional.of(new ServerTypeVersion(type, version));
    }

    @Override
    public String toString() {
        return "SessionInfo[" + this.id + " " + this.fields + "]";
    }
}
