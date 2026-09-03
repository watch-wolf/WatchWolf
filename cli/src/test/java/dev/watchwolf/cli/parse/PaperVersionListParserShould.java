package dev.watchwolf.cli.parse;

import dev.watchwolf.cli.model.McVersion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PaperVersionListParserShould {
    /** The real shape of fill.papermc.io/v3/projects/paper: versions grouped by family. */
    private static final String API_RESPONSE =
            "{\"project\":{\"id\":\"paper\",\"name\":\"Paper\"},"
          + "\"versions\":{"
          + "\"1.20\":[\"1.20.4\",\"1.20\"],"
          + "\"1.19\":[\"1.19.4\"],"
          + "\"1.8\":[\"1.8.8\"]"
          + "}}";

    @Test
    public void listTheVersionsNewestFirst() {
        assertEquals(
                List.of(McVersion.of("1.20.4"), McVersion.of("1.20"),
                        McVersion.of("1.19.4"), McVersion.of("1.8.8")),
                PaperVersionListParser.parse(API_RESPONSE));
    }

    @Test
    public void returnNothingForEmptyOrUnrelatedInput() {
        assertTrue(PaperVersionListParser.parse("").isEmpty());
        assertTrue(PaperVersionListParser.parse(null).isEmpty());
        assertTrue(PaperVersionListParser.parse("{\"error\":\"not found\"}").isEmpty());
    }

    @Test
    public void excludePreReleasesAndCandidates() {
        // a family's array mixes real releases with pre-releases/candidates; only exact
        // digits-and-dots strings count as a real, buildable version
        String json = "{\"versions\":{\"1.21\":[\"1.21.11\",\"1.21.11-rc3\",\"1.21.11-pre5\","
                + "\"1.21\"]}}";

        assertEquals(List.of(McVersion.of("1.21.11"), McVersion.of("1.21")),
                PaperVersionListParser.parse(json));
    }

    @Test
    public void includeVersionsInTheNewerNonOneXFamilies() {
        // Paper's family naming changed after 1.21 too (26.1, 26.2, ...), mirroring Spigot's
        String json = "{\"versions\":{\"26.1\":[\"26.1.2\",\"26.1.1\"]}}";

        assertEquals(List.of(McVersion.of("26.1.2"), McVersion.of("26.1.1")),
                PaperVersionListParser.parse(json));
    }

    @Test
    public void neverTreatAFamilyKeyAsAVersionOnItsOwn() {
        // "26.1" is only the family key here, never listed inside its own array (unlike the
        // older "1.x" families, where the bare version IS one of its own array's entries) -- it
        // must not be invented as a selectable version that the API can never actually build
        String json = "{\"versions\":{\"26.1\":[\"26.1.2\",\"26.1.1\"]}}";

        assertFalse(PaperVersionListParser.parse(json).contains(McVersion.of("26.1")));
    }

    @Test
    public void dropVersionsBelowTheMinimumSupported() {
        String json = "{\"versions\":{\"1.8\":[\"1.8.8\",\"1.8\"],\"1.7\":[\"1.7.10\"]}}";

        assertEquals(List.of(McVersion.of("1.8.8"), McVersion.of("1.8")),
                PaperVersionListParser.parse(json));
    }
}
