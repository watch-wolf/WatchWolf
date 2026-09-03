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
        List<McVersion> versions =
                SpigotVersionListParser.parse("1.8.8.json 1.8.8.json 1.8.8.json");
        assertEquals(List.of(McVersion.of("1.8.8")), versions);
    }

    @Test
    public void returnNothingForEmptyOrUnrelatedInput() {
        assertTrue(SpigotVersionListParser.parse("").isEmpty());
        assertTrue(SpigotVersionListParser.parse(null).isEmpty());
        assertTrue(SpigotVersionListParser.parse("<html>503 Service Unavailable</html>").isEmpty());
    }
}
