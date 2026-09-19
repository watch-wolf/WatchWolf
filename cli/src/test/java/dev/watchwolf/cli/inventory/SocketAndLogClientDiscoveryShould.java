package dev.watchwolf.cli.inventory;

import dev.watchwolf.cli.fake.FakeDockerFacade;
import dev.watchwolf.cli.model.Confidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SocketAndLogClientDiscoveryShould {
    /** 1B58=7000 (manager), 1B59=7001, 1B5A=7002 (images), 1B5B=7003. */
    private static final String PROC_NET_TCP = String.join("\n",
            "  sl  local_address rem_address   st tx_queue rx_queue",
            "   0: 00000000:1B58 00000000:0000 0A 00000000:00000000",
            "   1: 00000000:1B59 00000000:0000 0A 00000000:00000000",
            "   2: 00000000:1B5A 00000000:0000 0A 00000000:00000000",
            "   3: 00000000:1B5B 00000000:0000 0A 00000000:00000000");

    @Test
    public void reportNoBotsWhenTheManagerIsDown() {
        ClientDiscovery.Result result =
                new SocketAndLogClientDiscovery(new FakeDockerFacade()).discover(false);

        assertTrue(result.clients().isEmpty());
        assertNotNull(result.limitation());
    }

    @Test
    public void takeTheListeningPortsAsTheTruthAndTheLogAsDecoration() {
        FakeDockerFacade docker = new FakeDockerFacade()
                .withExecOutput("ClientsManager", PROC_NET_TCP)
                .withLogs("ClientsManager",
                        "Starting client Alice at server 127.0.0.1:8001...",
                        "Client started at 192.168.1.5:7001",
                        "[Alice - 127.0.0.1:8001] joined the game");

        List<ClientStatus> clients =
                new SocketAndLogClientDiscovery(docker).discover(true).clients();

        assertEquals(2, clients.size(), "7001 and 7003 listen; 7002 is the image half");
        assertEquals("Alice", clients.get(0).username().orElseThrow());
        assertEquals(Confidence.OBSERVED, clients.get(0).confidence());
        assertEquals("127.0.0.1:8001", clients.get(0).minecraftServer().orElseThrow());
    }

    @Test
    public void showAListeningPortWithNoNameAsBotAtItsPort() {
        // never borrow a plausible name -- an unnamed row is honest, a wrong one reaches a report
        FakeDockerFacade docker = new FakeDockerFacade()
                .withExecOutput("ClientsManager", PROC_NET_TCP)
                .withLogs("ClientsManager", "some unrelated noise");

        List<ClientStatus> clients =
                new SocketAndLogClientDiscovery(docker).discover(true).clients();

        assertEquals("bot@7001", clients.get(0).displayName());
        assertEquals(Confidence.INFERRED, clients.get(0).confidence());
        assertEquals("7001/7002", clients.get(0).portsLabel());
    }

    @Test
    public void neverInventARowFromTheLogAlone() {
        // the log knows Alice, but nothing is listening: she stopped
        FakeDockerFacade docker = new FakeDockerFacade()
                .withExecOutput("ClientsManager",
                        "  sl  local_address rem_address   st\n"
                      + "   0: 00000000:1B58 00000000:0000 0A")
                .withLogs("ClientsManager",
                        "Starting client Alice at server 127.0.0.1:8001...",
                        "Client started at 192.168.1.5:7001");

        ClientDiscovery.Result result = new SocketAndLogClientDiscovery(docker).discover(true);

        assertTrue(result.clients().isEmpty());
        assertTrue(result.limitation().contains("no longer listening"));
    }

    @Test
    public void fallBackToTheLogAndSaySoWhenTheSocketTableCannotBeRead() {
        FakeDockerFacade docker = new FakeDockerFacade()
                .withLogs("ClientsManager",
                        "Starting client Alice at server 127.0.0.1:8001...",
                        "Client started at 192.168.1.5:7001")
                .withExecFailing(new RuntimeException("exec is not permitted"));

        ClientDiscovery.Result result = new SocketAndLogClientDiscovery(docker).discover(true);

        assertEquals(1, result.clients().size());
        assertEquals(Confidence.INFERRED, result.clients().get(0).confidence());
        assertTrue(result.sourceLabel().contains("from log"));
        assertTrue(result.limitation().contains("already stopped"));
    }

    @Test
    public void readTheSocketTableFromInsideTheContainer() {
        // reading /proc/net/tcp in the container is the only reliable way: the manager publishes
        // 7000-7199, so docker-proxy accepts on every host port whether a bot exists or not
        FakeDockerFacade docker = new FakeDockerFacade()
                .withExecOutput("ClientsManager", PROC_NET_TCP);

        new SocketAndLogClientDiscovery(docker).discover(true);

        assertArrayEquals(new String[] { "cat", "/proc/net/tcp", "/proc/net/tcp6" },
                docker.execCalls().get(0));
    }
}
