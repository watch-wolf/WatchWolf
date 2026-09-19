package dev.watchwolf.cli.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The note a background install leaves behind for whoever runs {@code build} next. */
public class InstallRunRecordShould {

    @Test
    public void surviveARoundTrip() {
        InstallRunRecord original = new InstallRunRecord("backgrounded", "install successful",
                "2026-09-04T10:00:00Z", List.of());
        InstallRunRecord restored = InstallRunRecord.parse(original.render());

        assertEquals("backgrounded", restored.ending());
        assertEquals("install successful", restored.summary());
        assertEquals("2026-09-04T10:00:00Z", restored.finishedAt());
        assertTrue(restored.succeeded());
    }

    @Test
    public void keepEveryFailureOnItsOwnLine() {
        InstallRunRecord original = new InstallRunRecord("backgrounded",
                "install failed: 2 step(s) of 9", "2026-09-04T10:00:00Z",
                List.of("build-spigot: building Spigot: 1 version(s) failed",
                        "self-diagnosis: running the suites: blocked"));

        InstallRunRecord restored = InstallRunRecord.parse(original.render());

        assertEquals(2, restored.failures().size());
        assertFalse(restored.succeeded());
        assertTrue(restored.failures().get(0).startsWith("build-spigot:"));
    }

    @Test
    public void flattenAMultiLineFailureSoItStaysOneRecord() {
        // a step failure lists its versions on their own lines; left as-is, the second line would
        // read back as a key of its own and the record would lose the rest of the file
        InstallRunRecord original = new InstallRunRecord("backgrounded", "install failed",
                "2026-09-04T10:00:00Z", List.of("build-spigot: failed:\n  1.8.8: no jar"));

        InstallRunRecord restored = InstallRunRecord.parse(original.render());

        assertEquals(1, restored.failures().size());
        assertEquals("install failed", restored.summary());
        assertTrue(restored.failures().get(0).contains("1.8.8: no jar"));
    }
}
