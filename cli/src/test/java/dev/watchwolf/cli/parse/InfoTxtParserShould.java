package dev.watchwolf.cli.parse;

import dev.watchwolf.cli.model.SessionInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class InfoTxtParserShould {
    /** A real file, from ci/debug/logs/1764252731951/info.txt. */
    private static final String REAL_SAMPLE = String.join("\n",
            "createdAt = 27/11/2025 14:12:12",
            "serverVersion = 1.19",
            "serverType = Spigot",
            "ip = 79.157.230.19:8001",
            "uuid = 1764252731951");

    @Test
    public void readTheFieldsOfARealFile() {
        SessionInfo info = InfoTxtParser.parse("1764252731951", REAL_SAMPLE);

        assertEquals("Spigot", info.serverType().orElseThrow());
        assertEquals("1.19", info.serverVersion().orElseThrow());
        assertEquals("79.157.230.19:8001", info.advertisedIp().orElseThrow());
        assertEquals("27/11/2025 14:12:12", info.createdAt().orElseThrow());
        assertEquals("1764252731951", info.id());
    }

    @Test
    public void acceptKeysInAnyOrder() {
        // the ServersManager iterates a HashMap when writing this, so the order is hash order,
        // not insertion order -- nothing may read it positionally
        String reordered = String.join("\n",
                "uuid = 1764252731951",
                "ip = 79.157.230.19:8001",
                "serverType = Spigot",
                "serverVersion = 1.19",
                "createdAt = 27/11/2025 14:12:12");

        assertEquals(InfoTxtParser.parse("x", REAL_SAMPLE).fields(),
                     InfoTxtParser.parse("x", reordered).fields());
    }

    @Test
    public void keepTheLastValueWhenAKeyRepeats() {
        // it writes with StandardOpenOption.APPEND, so re-running with the same id duplicates
        // every key; the most recent run is the one worth reporting
        SessionInfo info = InfoTxtParser.parse("x",
                "serverVersion = 1.19\nserverType = Spigot\nserverVersion = 1.20.4");
        assertEquals("1.20.4", info.serverVersion().orElseThrow());
    }

    @Test
    public void surviveAMalformedOrEmptyFile() {
        assertTrue(InfoTxtParser.parse("x", "").fields().isEmpty());
        assertTrue(InfoTxtParser.parse("x", null).fields().isEmpty());
        assertTrue(InfoTxtParser.parse("x", "garbage with no separator").fields().isEmpty());
    }

    @Test
    public void exposeTypeAndVersionTogetherWhenBothArePresent() {
        assertEquals("Spigot 1.19",
                InfoTxtParser.parse("x", REAL_SAMPLE).typeAndVersion().orElseThrow().toString());
        assertTrue(InfoTxtParser.parse("x", "serverType = Spigot").typeAndVersion().isEmpty());
    }
}
