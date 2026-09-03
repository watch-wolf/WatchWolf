package dev.watchwolf.cli.parse;

import dev.watchwolf.cli.model.McVersion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SpigotVersionListParserShould {
    /** The shape of hub.spigotmc.org/versions/ -- an Apache-style index of <version>.json files. */
    private static final String INDEX = String.join("\n",
            "<a href=\"1.8.8.json\">1.8.8.json</a>",
            "<a href=\"1.20.4.json\">1.20.4.json</a>",
            "<a href=\"1.20.json\">1.20.json</a>",
            "<a href=\"1.9.json\">1.9.json</a>",
            "<a href=\"latest.json\">latest.json</a>");

    @Test
    public void listTheVersionsNewestFirst() {
        assertEquals(
                List.of(McVersion.of("1.20.4"), McVersion.of("1.20"),
                        McVersion.of("1.9"), McVersion.of("1.8.8")),
                SpigotVersionListParser.parse(INDEX));
    }

    @Test
    public void keepVersionsThatAppearOnlyOnce() {
        // SpigotBuilder.sh ends its pipeline in `uniq -d`, which prints ONLY duplicated lines, so
        // a version listed once was silently undownloadable. Dropping that flag is the fix.
        assertTrue(SpigotVersionListParser.parse("<a href=\"1.20.6.json\">x</a>")
                        .contains(McVersion.of("1.20.6")));
    }

    @Test
    public void deduplicateRepeatedVersions() {
        String html = "<a href=\"1.8.8.json\">x</a> <a href=\"1.8.8.json\">x</a> "
                + "<a href=\"1.8.8.json\">x</a>";
        assertEquals(List.of(McVersion.of("1.8.8")), SpigotVersionListParser.parse(html));
    }

    @Test
    public void returnNothingForEmptyOrUnrelatedInput() {
        assertTrue(SpigotVersionListParser.parse("").isEmpty());
        assertTrue(SpigotVersionListParser.parse(null).isEmpty());
        assertTrue(SpigotVersionListParser.parse("<html>503 Service Unavailable</html>").isEmpty());
    }

    @Test
    public void includeVersionsInSpigotsNewerNonOneXNumbering() {
        // Spigot moved away from "1.x" numbering after 1.21 (e.g. 26.1, 26.2); the old pattern
        // required a leading "1." and silently dropped every one of these
        assertEquals(List.of(McVersion.of("26.1")),
                SpigotVersionListParser.parse("<a href=\"26.1.json\">26.1.json</a>"));
    }

    @Test
    public void neverInventAPhantomVersionFromInsideALongerFileName() {
        // the real bug report: hub.spigotmc.org now lists 26.1.1.json and 26.1.2.json, and the
        // old unanchored pattern's find() matched the EMBEDDED substring "1.1.json"/"1.2.json"
        // inside them, reporting versions 1.1 and 1.2 that were never actually listed anywhere
        String html = "<a href=\"26.1.1.json\">26.1.1.json</a> <a href=\"26.1.2.json\">26.1.2.json</a>";
        List<McVersion> versions = SpigotVersionListParser.parse(html);

        assertEquals(List.of(McVersion.of("26.1.2"), McVersion.of("26.1.1")), versions);
        assertFalse(versions.contains(McVersion.of("1.1")), "1.1 was never really listed");
        assertFalse(versions.contains(McVersion.of("1.2")), "1.2 was never really listed");
    }

    @Test
    public void excludeBuildToolsOwnPerBuildMetadataFiles() {
        // the index also lists thousands of bare-integer files like 2600.json, 4617.json -- these
        // are BuildTools' own Jenkins build numbers, not Minecraft versions, and have no dot
        String html = "<a href=\"1.8.8.json\">1.8.8.json</a> <a href=\"2600.json\">2600.json</a> "
                + "<a href=\"263.json\">263.json</a>";
        assertEquals(List.of(McVersion.of("1.8.8")), SpigotVersionListParser.parse(html));
    }

    @Test
    public void dropVersionsBelowTheMinimumSupported() {
        // WatchWolf does not support anything older than 1.8, regardless of what the index lists
        String html = "<a href=\"1.8.json\">1.8.json</a> <a href=\"1.7.10.json\">1.7.10.json</a> "
                + "<a href=\"1.2.json\">1.2.json</a>";
        assertEquals(List.of(McVersion.of("1.8")), SpigotVersionListParser.parse(html));
    }
}
