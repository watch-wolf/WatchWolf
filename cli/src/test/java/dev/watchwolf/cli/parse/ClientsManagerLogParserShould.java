package dev.watchwolf.cli.parse;

import dev.watchwolf.cli.model.ClientLogEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ClientsManagerLogParserShould {
    @Test
    public void readTheMinecraftServerAddressFromThePrefixNotTheBotPort() {
        // MineflayerClient's printer is [username - <mc host>:<mc port>]. That port is the SERVER's,
        // not the bot's 700x connector port. Reading it as the bot's port would put every bot on
        // the wrong row, so this is pinned.
        ClientLogEvent event = ClientsManagerLogParser.parseLine(
                "[MinecraftGamer_Z - 127.0.0.1:8001] Breaking block at (1, 2, 3)");

        assertEquals(ClientLogEvent.Kind.CLIENT_MESSAGE, event.kind());
        assertEquals("MinecraftGamer_Z", event.username().orElseThrow());
        assertEquals("127.0.0.1:8001", event.minecraftServerAddress().orElseThrow());
        assertEquals("Breaking block at (1, 2, 3)", event.message().orElseThrow());
    }

    @Test
    public void readTheBotPortOnlyFromTheClientStartedLine() {
        ClientLogEvent event = ClientsManagerLogParser.parseLine("Client started at 192.168.1.5:7001");

        assertEquals(ClientLogEvent.Kind.CLIENT_STARTED, event.kind());
        assertEquals(7001, event.port().orElseThrow());
        assertTrue(event.username().isEmpty(), "this line never names the bot");
    }

    @Test
    public void readTheUsernameFromTheStartingLine() {
        ClientLogEvent event =
                ClientsManagerLogParser.parseLine("Starting client Steve at server 127.0.0.1:8001...");

        assertEquals(ClientLogEvent.Kind.CLIENT_STARTING, event.kind());
        assertEquals("Steve", event.username().orElseThrow());
    }

    @Test
    public void acceptUsernamesContainingSpacesAndDashes() {
        ClientLogEvent event =
                ClientsManagerLogParser.parseLine("[Some Bot-1 - example.com:25565] hi");
        assertEquals("Some Bot-1", event.username().orElseThrow());
        assertEquals("example.com", event.host().orElseThrow());
    }

    @Test
    public void ignoreLinesItCannotUse() {
        assertNull(ClientsManagerLogParser.parseLine("Unknown request: 42"));
        assertNull(ClientsManagerLogParser.parseLine(""));
        assertNull(ClientsManagerLogParser.parseLine(null));
    }
}
