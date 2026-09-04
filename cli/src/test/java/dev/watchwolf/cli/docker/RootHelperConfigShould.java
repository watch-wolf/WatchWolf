package dev.watchwolf.cli.docker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RootHelperConfigShould {
    @Test
    public void beAvailableOnlyWhenAllThreeValuesArePresent() {
        assertTrue(new RootHelperConfig("img", "1000", "1000").isAvailable());
        assertFalse(new RootHelperConfig(null, "1000", "1000").isAvailable());
        assertFalse(new RootHelperConfig("img", null, "1000").isAvailable());
        assertFalse(new RootHelperConfig("img", "1000", null).isAvailable());
        assertFalse(new RootHelperConfig(null, null, null).isAvailable());
    }
}
