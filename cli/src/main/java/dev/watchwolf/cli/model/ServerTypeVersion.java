package dev.watchwolf.cli.model;

import java.util.Objects;

/**
 * One entry of {@code server-types/}: a type folder plus a version jar, e.g. {@code Spigot 1.8.8}.
 *
 * <p>Any folder name is a valid server type -- {@code CustomSpigot/1.20.4.jar} works -- so the type
 * is a free string, never an enum.
 */
public final class ServerTypeVersion implements Comparable<ServerTypeVersion> {
    private final String type;
    private final McVersion version;

    public ServerTypeVersion(String type, McVersion version) {
        this.type = Objects.requireNonNull(type, "type");
        this.version = Objects.requireNonNull(version, "version");
    }

    public static ServerTypeVersion of(String type, String version) {
        return new ServerTypeVersion(type, McVersion.of(version));
    }

    public String type()       { return this.type; }
    public McVersion version() { return this.version; }
    public String jarName()    { return this.version + ".jar"; }

    /** The JDK image this server will be launched on. */
    public String requiredImage() {
        return JavaImageCatalog.imageFor(this.version);
    }

    @Override
    public int compareTo(ServerTypeVersion other) {
        int byType = this.type.compareToIgnoreCase(other.type);
        return byType != 0 ? byType : this.version.compareTo(other.version);
    }

    @Override
    public String toString() {
        return this.type + " " + this.version;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ServerTypeVersion)) return false;
        ServerTypeVersion other = (ServerTypeVersion) o;
        return this.type.equals(other.type) && this.version.equals(other.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.type, this.version);
    }
}
