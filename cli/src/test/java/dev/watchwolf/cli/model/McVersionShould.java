package dev.watchwolf.cli.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class McVersionShould {
    @Test
    public void compareNumericallyNotAlphabetically() {
        // the two orderings plain string comparison gets wrong
        assertTrue(McVersion.of("1.10").compareTo(McVersion.of("1.9")) > 0);
        assertTrue(McVersion.of("1.20.5").compareTo(McVersion.of("1.20.4")) > 0);
    }

    @Test
    public void treatMissingComponentsAsZero() {
        assertEquals(McVersion.of("1.20"), McVersion.of("1.20.0"));
        assertEquals(McVersion.of("1.20").hashCode(), McVersion.of("1.20.0").hashCode());
    }

    @Test
    public void placeLatestAboveEveryVersion() {
        assertTrue(McVersion.LATEST.compareTo(McVersion.of("1.21")) > 0);
        assertTrue(McVersion.of("1.21").compareTo(McVersion.LATEST) < 0);
        assertEquals(McVersion.LATEST, McVersion.of("LATEST"));
    }

    @Test
    public void returnNullRatherThanThrowForNonVersions() {
        assertNull(McVersion.parseOrNull("not-a-version"));
        assertNull(McVersion.parseOrNull(""));
        assertThrows(IllegalArgumentException.class, () -> McVersion.of("1.x"));
    }

    @Test
    public void roundTripThroughToString() {
        assertEquals("1.8.8", McVersion.of("1.8.8").toString());
        assertEquals("LATEST", McVersion.LATEST.toString());
    }
}
