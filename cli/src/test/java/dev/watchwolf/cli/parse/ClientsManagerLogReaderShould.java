package dev.watchwolf.cli.parse;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ClientsManagerLogReaderShould {
    @Test
    public void pairAUsernameWithThePortStartedRightAfterIt() {
        ClientsManagerLogReader reader = ClientsManagerLogReader.over(List.of(
                "Starting client MinecraftGamer_Z at server 127.0.0.1:8001...",
                "Client started at 192.168.1.5:7001"));

        assertEquals("MinecraftGamer_Z", reader.usernameForPort(7001).orElseThrow());
        assertTrue(reader.unattributedPorts().isEmpty());
    }

    @Test
    public void notPairAStartWithAnotherConnectionsFinish() {
        // ClientsManagerConnector runs one thread per Tester connection, so two Testers starting
        // bots at once interleave these lines. Guessing here would put a real username on the
        // wrong port -- and that wrong name would travel into a bug report.
        ClientsManagerLogReader reader = ClientsManagerLogReader.over(List.of(
                "Starting client Alice at server 127.0.0.1:8001...",
                "Starting client Bob at server 127.0.0.1:8003...",
                "Client started at 192.168.1.5:7001"));

        assertTrue(reader.usernameForPort(7001).isEmpty(),
                "the port cannot be attributed to either bot");
        assertEquals(java.util.Set.of(7001), reader.unattributedPorts());
        assertEquals(java.util.Set.of("Alice", "Bob"), reader.usernamesSeen());
    }

    @Test
    public void breakAdjacencyWhenAClientMessageIntervenes() {
        ClientsManagerLogReader reader = ClientsManagerLogReader.over(List.of(
                "Starting client Alice at server 127.0.0.1:8001...",
                "[Bob - 127.0.0.1:8003] joined the game",
                "Client started at 192.168.1.5:7001"));

        assertTrue(reader.usernameForPort(7001).isEmpty());
    }

    @Test
    public void recordWhichMinecraftServerEachBotJoined() {
        ClientsManagerLogReader reader = ClientsManagerLogReader.over(List.of(
                "[Alice - 127.0.0.1:8001] joined the game",
                "[Bob - 127.0.0.1:8003] joined the game"));

        assertEquals("127.0.0.1:8001", reader.minecraftServerFor("Alice").orElseThrow());
        assertEquals("127.0.0.1:8003", reader.minecraftServerFor("Bob").orElseThrow());
    }

    @Test
    public void notLetUnrecognisedLinesBreakAdjacency() {
        // the log is full of mineflayer chatter; only recognised events may affect pairing
        ClientsManagerLogReader reader = ClientsManagerLogReader.over(List.of(
                "Starting client Alice at server 127.0.0.1:8001...",
                "some unrelated mineflayer noise",
                "Client started at 192.168.1.5:7001"));

        assertEquals("Alice", reader.usernameForPort(7001).orElseThrow());
    }
}
