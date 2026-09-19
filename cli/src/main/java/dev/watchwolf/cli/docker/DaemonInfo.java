package dev.watchwolf.cli.docker;

/**
 * Identity of the daemon we are talking to.
 *
 * <p>{@code platformName} matters more than it looks: {@code --network host} only exposes the
 * host's real interfaces on Linux and WSL2. Under Docker Desktop for macOS/Windows it does not,
 * which would make the multi-NIC advice in {@code doctor} -- the entire point of that check --
 * confidently wrong. Detecting it lets us say the view is the container's instead of pretending.
 */
public record DaemonInfo(String serverVersion, String apiVersion, String platformName,
                         String operatingSystem, boolean reachable, String unreachableReason) {

    public static DaemonInfo unreachable(String reason) {
        return new DaemonInfo(null, null, null, null, false, reason);
    }

    public boolean isDockerDesktop() {
        return this.platformName != null && this.platformName.toLowerCase().contains("docker desktop");
    }

    /** Whether {@code --network host} really shows the host's interfaces. */
    public boolean hostNetworkingIsTruthful() {
        return !this.isDockerDesktop();
    }
}
