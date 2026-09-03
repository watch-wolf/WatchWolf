package dev.watchwolf.cli.docker;

import java.util.Objects;

/** One published port: {@code <hostPort> -> <containerPort>/<protocol>}. */
public final class PortBindingInfo implements Comparable<PortBindingInfo> {
    private final int hostPort;
    private final int containerPort;
    private final String protocol;

    public PortBindingInfo(int hostPort, int containerPort, String protocol) {
        this.hostPort = hostPort;
        this.containerPort = containerPort;
        this.protocol = protocol == null ? "tcp" : protocol.toLowerCase();
    }

    public int hostPort()      { return this.hostPort; }
    public int containerPort() { return this.containerPort; }
    public String protocol()   { return this.protocol; }
    public boolean isTcp()     { return "tcp".equals(this.protocol); }

    @Override
    public int compareTo(PortBindingInfo other) {
        int byHost = Integer.compare(this.hostPort, other.hostPort);
        if (byHost != 0) return byHost;
        return this.protocol.compareTo(other.protocol);
    }

    @Override
    public String toString() {
        return this.hostPort + "->" + this.containerPort + "/" + this.protocol;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PortBindingInfo)) return false;
        PortBindingInfo other = (PortBindingInfo) o;
        return this.hostPort == other.hostPort
                && this.containerPort == other.containerPort
                && this.protocol.equals(other.protocol);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.hostPort, this.containerPort, this.protocol);
    }
}
