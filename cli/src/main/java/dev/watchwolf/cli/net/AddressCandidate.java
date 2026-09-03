package dev.watchwolf.cli.net;

import java.util.Objects;

/**
 * One address the ServersManager could end up advertising, and how suspicious it is.
 *
 * <p>This models the bug that started the whole effort: on a machine with a VirtualBox host-only
 * adapter, {@code hostname -I | awk '{print $1}'} picked {@code 192.168.56.1}, the ServersManager
 * handed that to the Tester, and nothing on the test host could reach it. Enumerating the
 * candidates and <em>ranking</em> them is what lets {@code doctor} say so at install time.
 */
public final class AddressCandidate implements Comparable<AddressCandidate> {
    /** Higher is more likely to be the address a Tester can actually reach. */
    public enum Kind {
        VIRTUALBOX_HOST_ONLY("VirtualBox host-only", 10),
        DOCKER_BRIDGE("Docker bridge", 20),
        WSL_VETHERNET("WSL vEthernet", 30),
        LOOPBACK("loopback", 40),
        OTHER_PRIVATE("private", 60),
        LAN("LAN", 100);

        private final String label;
        private final int score;

        Kind(String label, int score) {
            this.label = label;
            this.score = score;
        }

        public String label() { return this.label; }
        public int score()    { return this.score; }
    }

    private final String address;
    private final String interfaceName;
    private final Kind kind;

    public AddressCandidate(String address, String interfaceName, Kind kind) {
        this.address = Objects.requireNonNull(address);
        this.interfaceName = interfaceName;
        this.kind = kind;
    }

    public String address()       { return this.address; }
    public String interfaceName() { return this.interfaceName; }
    public Kind kind()            { return this.kind; }

    /** Would picking this one probably break a Tester on another machine? */
    public boolean isSuspicious() {
        return this.kind == Kind.VIRTUALBOX_HOST_ONLY
                || this.kind == Kind.DOCKER_BRIDGE
                || this.kind == Kind.LOOPBACK;
    }

    /** Best first. */
    @Override
    public int compareTo(AddressCandidate other) {
        int byScore = Integer.compare(other.kind.score(), this.kind.score());
        return byScore != 0 ? byScore : this.address.compareTo(other.address);
    }

    @Override
    public String toString() {
        return this.address + " (" + this.interfaceName + ", " + this.kind.label() + ")";
    }
}
