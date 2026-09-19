package dev.watchwolf.cli.inventory;

import dev.watchwolf.cli.fake.FakeDockerFacade;
import dev.watchwolf.cli.io.NioFileGateway;
import dev.watchwolf.cli.layout.InstallLayout;
import dev.watchwolf.cli.layout.RuntimeFlavor;
import dev.watchwolf.cli.model.Confidence;
import dev.watchwolf.cli.net.HostInterfaces;
import dev.watchwolf.cli.net.PortProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class EnvironmentScannerShould {
    @TempDir Path base;

    private InstallLayout layout;
    private FakeDockerFacade docker;

    /** Nothing is listening in a unit test, so the probe must not decide reachability. */
    private static final PortProbe ALWAYS_ACCEPTING = new PortProbe() {
        @Override public boolean isAccepting(String host, int port) { return true; }
    };
    private static final PortProbe NEVER_ACCEPTING = new PortProbe() {
        @Override public boolean isAccepting(String host, int port) { return false; }
    };

    private static final HostInterfaces FIXED_ADDRESS = new HostInterfaces() {
        @Override public String preferredMachineIp() { return "192.168.1.193"; }
    };

    @BeforeEach
    void setUp() {
        this.layout = new InstallLayout(this.base, RuntimeFlavor.RELEASE);
        this.docker = new FakeDockerFacade();
    }

    private EnvironmentScanner scanner(PortProbe probe, ClientDiscovery clients) {
        return new EnvironmentScanner(this.docker, new NioFileGateway(), this.layout, clients,
                probe, FIXED_ADDRESS, Clock.fixed(Instant.parse("2026-09-03T12:00:00Z"), ZoneOffset.UTC));
    }

    private ClientDiscovery noClients() {
        return running -> new ClientDiscovery.Result(List.of(), "clients (0)", null);
    }

    private void writeInfoTxt(String sessionId, String type, String version) throws IOException {
        Path info = this.layout.sessionInfoFile(sessionId);
        Files.createDirectories(info.getParent());
        Files.writeString(info, "serverVersion = " + version + "\nserverType = " + type + "\n");
    }

    @Test
    public void reportBothManagersAsOfflineWhenNoContainersExist() {
        EnvironmentSnapshot snapshot = this.scanner(NEVER_ACCEPTING, this.noClients()).scan();

        assertFalse(snapshot.serversManager().isUp());
        assertFalse(snapshot.clientsManager().isUp());
        assertEquals("offline", snapshot.serversManager().stateLabel());
        assertFalse(snapshot.anythingRunning());
    }

    @Test
    public void distinguishRunningFromActuallyAccepting() {
        // a container that is up but not yet listening is where every "Connection refused"
        // report begins, so the two states must not be collapsed
        this.docker.withContainer("ServersManager").running().publishing(8000, 8000).done();

        assertEquals("starting",
                this.scanner(NEVER_ACCEPTING, this.noClients()).scan().serversManager().stateLabel());
        assertEquals("online",
                this.scanner(ALWAYS_ACCEPTING, this.noClients()).scan().serversManager().stateLabel());
    }

    @Test
    public void pairTheGamePortWithTheSocketPort() throws IOException {
        this.docker.withContainer("MC_Server-1772387923303").running()
                .publishing(8001, 25565).publishingUdp(8001, 25565).publishing(8002, 25566).done();
        this.writeInfoTxt("1772387923303", "Spigot", "1.8.8");

        McServerStatus server =
                this.scanner(ALWAYS_ACCEPTING, this.noClients()).scan().servers().get(0);

        assertEquals("8001/8002", server.portsLabel());
        assertEquals("Spigot", server.type());
        assertEquals("1.8.8", server.version());
        assertEquals(Confidence.OBSERVED, server.confidence());
    }

    @Test
    public void showAnMcServerWithNoInfoTxtAsInferred() {
        // a running container the ServersManager has lost track of is exactly what an orphaned
        // server looks like; hiding it would hide the bug
        this.docker.withContainer("MC_Server-1772387923303").running()
                .publishing(8001, 25565).publishing(8002, 25566).done();

        McServerStatus server =
                this.scanner(ALWAYS_ACCEPTING, this.noClients()).scan().servers().get(0);

        assertEquals(Confidence.INFERRED, server.confidence());
        assertEquals("?", server.type());
        assertTrue(server.isRunning());
    }

    @Test
    public void listFinishedRunsThatOnlyLeftALogsFolder() throws IOException {
        // MC server containers run with --autoRemove, so Docker has nothing on them once they
        // stop -- but logs/<id>/ survives, which is why the monitor tails the file
        this.writeInfoTxt("1772387923303", "Paper", "1.20.4");

        List<McServerStatus> servers =
                this.scanner(ALWAYS_ACCEPTING, this.noClients()).scan().servers();

        assertEquals(1, servers.size());
        assertTrue(servers.get(0).isHistoricalOnly());
        assertFalse(servers.get(0).isRunning());
        assertEquals("Paper", servers.get(0).type());
    }

    @Test
    public void sortRunningServersBeforeFinishedOnes() throws IOException {
        this.writeInfoTxt("1000000000001", "Spigot", "1.8.8");            // finished
        this.docker.withContainer("MC_Server-1000000000002").running()
                .publishing(8001, 25565).publishing(8002, 25566).done();  // running
        this.writeInfoTxt("1000000000002", "Paper", "1.20.4");

        List<McServerStatus> servers =
                this.scanner(ALWAYS_ACCEPTING, this.noClients()).scan().servers();

        assertEquals("1000000000002", servers.get(0).sessionId());
        assertTrue(servers.get(0).isRunning());
    }

    @Test
    public void ignoreContainersThatMerelyLookLikeServers() {
        this.docker.withContainer("my-MC_Server-123").running().done();
        this.docker.withContainer("MC_Server-notanumber").running().done();

        assertTrue(this.scanner(ALWAYS_ACCEPTING, this.noClients()).scan().servers().isEmpty());
    }

    @Test
    public void degradeToAnEmptyPictureWhenDockerIsUnreachable() {
        this.docker.withDaemonUnreachable("Cannot connect to the Docker daemon");

        EnvironmentSnapshot snapshot = this.scanner(ALWAYS_ACCEPTING, this.noClients()).scan();

        assertFalse(snapshot.dockerReachable());
        assertEquals("Cannot connect to the Docker daemon",
                snapshot.dockerUnreachableReason().orElseThrow());
        assertTrue(snapshot.servers().isEmpty());
        assertFalse(snapshot.anythingRunning());
    }

    @Test
    public void joinABotToTheServerItReportedJoining() throws IOException {
        this.docker.withContainer("MC_Server-1772387923303").running()
                .publishing(8001, 25565).publishing(8002, 25566).done();
        this.writeInfoTxt("1772387923303", "Spigot", "1.8.8");

        ClientStatus bot = new ClientStatus(7001, "Alice", "127.0.0.1:8001", Confidence.OBSERVED);
        EnvironmentSnapshot snapshot = this.scanner(ALWAYS_ACCEPTING,
                running -> new ClientDiscovery.Result(List.of(bot), "clients (1)", null)).scan();

        assertEquals("1772387923303",
                snapshot.serverForClient(bot).orElseThrow().sessionId());
    }
}
