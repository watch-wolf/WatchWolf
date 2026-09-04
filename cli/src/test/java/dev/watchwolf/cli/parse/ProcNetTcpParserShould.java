package dev.watchwolf.cli.parse;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class ProcNetTcpParserShould {
    // 1B58 = 7000 (the manager), 1B59 = 7001 and 1B5B = 7003 (two bots), 1B5A = 7002 is the
    // image-stream half of the first bot's pair. The 0016 entry is in state 01 (ESTABLISHED).
    private static final String PROC_NET_TCP = String.join("\n",
            "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid",
            "   0: 00000000:1B58 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000",
            "   1: 00000000:1B59 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000",
            "   2: 00000000:1B5A 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000",
            "   3: 00000000:1B5B 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000",
            "   4: 0100007F:0016 0100007F:E1F5 01 00000000:00000000 00:00000000 00000000  1000");

    @Test
    public void decodeLittleEndianHexPortsInListenState() {
        assertEquals(Set.of(7000, 7001, 7002, 7003),
                ProcNetTcpParser.listeningPorts(PROC_NET_TCP));
    }

    @Test
    public void ignoreSocketsThatAreNotListening() {
        assertFalse(ProcNetTcpParser.listeningPorts(PROC_NET_TCP).contains(22));
    }

    @Test
    public void keepOnlyTheConnectorHalfOfEachClientPortPair() {
        // bots get a consecutive pair from 7001 stepping by 2: odd = connector, even = images.
        // Only the connector identifies a bot; counting both would double every bot.
        assertEquals(Set.of(7001, 7003),
                ProcNetTcpParser.clientConnectorPorts(PROC_NET_TCP, 7000, 7199));
    }

    @Test
    public void excludeTheManagerPortItself() {
        assertFalse(ProcNetTcpParser.clientConnectorPorts(PROC_NET_TCP, 7000, 7199).contains(7000));
    }

    @Test
    public void surviveAnEmptyOrHeaderOnlyTable() {
        assertTrue(ProcNetTcpParser.listeningPorts("").isEmpty());
        assertTrue(ProcNetTcpParser.listeningPorts(null).isEmpty());
        assertTrue(ProcNetTcpParser.listeningPorts("  sl  local_address rem_address   st").isEmpty());
    }
}
