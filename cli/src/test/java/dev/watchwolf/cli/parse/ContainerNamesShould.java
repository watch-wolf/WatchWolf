package dev.watchwolf.cli.parse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ContainerNamesShould {
    @Test
    public void stripTheLeadingSlashDockerReports() {
        // Docker returns names as "/ServersManager". The ServersManager's own
        // closeAllLaunchedServers compares ids against those raw names and so never matches
        // anything; we normalise instead of inheriting that bug.
        assertEquals("ServersManager", ContainerNames.normalise("/ServersManager"));
        assertEquals("ServersManager", ContainerNames.normalise("ServersManager"));
        assertNull(ContainerNames.normalise(null));
    }

    @Test
    public void extractTheSessionIdThatJoinsAContainerToItsLogs() {
        // the id is shared by MC_Server-<id>, tmp/<id>/ and logs/<id>/
        assertEquals("1772387923303",
                ContainerNames.mcServerSessionId("MC_Server-1772387923303").orElseThrow());
        assertEquals("1772387923303",
                ContainerNames.mcServerSessionId("/MC_Server-1772387923303").orElseThrow());
    }

    @Test
    public void notMistakeOtherContainersForServers() {
        assertTrue(ContainerNames.mcServerSessionId("ServersManager").isEmpty());
        assertTrue(ContainerNames.mcServerSessionId("MC_Server-notanumber").isEmpty());
        assertFalse(ContainerNames.isMcServer("my-MC_Server-123"));
    }

    @Test
    public void recogniseSpigotBuildContainers() {
        assertEquals("1.20.4",
                ContainerNames.spigotBuilderVersion("Spigot_build_1.20.4").orElseThrow());
        assertEquals("Spigot_build_1.20.4", ContainerNames.spigotBuilderFor("1.20.4"));
    }
}
