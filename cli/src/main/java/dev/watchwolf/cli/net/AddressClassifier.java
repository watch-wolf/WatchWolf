package dev.watchwolf.cli.net;

/**
 * Decides what kind of address we are looking at, from the address and its interface name.
 *
 * <p>Pure, so the whole multi-NIC diagnosis is unit-testable without touching a real NIC.
 */
public final class AddressClassifier {
    private AddressClassifier() {
    }

    public static AddressCandidate.Kind classify(String address, String interfaceName) {
        String name = interfaceName == null ? "" : interfaceName.toLowerCase();

        if (address.startsWith("127.")) return AddressCandidate.Kind.LOOPBACK;

        // The exact adapter from the original bug report. VirtualBox's host-only network is
        // 192.168.56.0/24 by default, and it is reachable only from that host.
        if (address.startsWith("192.168.56.")
                || name.contains("vboxnet") || name.contains("virtualbox")) {
            return AddressCandidate.Kind.VIRTUALBOX_HOST_ONLY;
        }

        if (name.startsWith("docker") || name.startsWith("br-") || name.startsWith("veth")
                || address.startsWith("172.17.") || address.startsWith("172.18.")) {
            return AddressCandidate.Kind.DOCKER_BRIDGE;
        }

        if (name.contains("vethernet") || name.contains("wsl")) {
            return AddressCandidate.Kind.WSL_VETHERNET;
        }

        if (address.startsWith("192.168.") || address.startsWith("10.")) {
            return AddressCandidate.Kind.LAN;
        }

        if (address.startsWith("172.")) {
            // 172.16.0.0/12 is private; anything else in 172.* is public
            try {
                int second = Integer.parseInt(address.split("\\.")[1]);
                if (second >= 16 && second <= 31) return AddressCandidate.Kind.OTHER_PRIVATE;
            } catch (RuntimeException ignored) {
                // malformed; fall through
            }
        }

        return AddressCandidate.Kind.OTHER_PRIVATE;
    }
}
