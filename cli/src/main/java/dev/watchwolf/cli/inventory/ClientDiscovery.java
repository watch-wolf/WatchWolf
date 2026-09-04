package dev.watchwolf.cli.inventory;

import java.util.List;

/** Finds the client bots living inside the ClientsManager container. */
public interface ClientDiscovery {

    /**
     * @param managerRunning whether the ClientsManager container is up at all
     * @return the bots, and how confident we are about each
     */
    Result discover(boolean managerRunning);

    /**
     * The bots plus a one-line description of how they were found, which the monitor shows as the
     * panel header and the bundle records verbatim.
     */
    record Result(List<ClientStatus> clients, String sourceLabel, String limitation) {
        public static Result unavailable(String reason) {
            return new Result(List.of(), "clients (unknown)", reason);
        }
    }
}
