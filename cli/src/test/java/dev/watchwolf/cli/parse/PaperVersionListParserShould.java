package dev.watchwolf.cli.parse;

import dev.watchwolf.cli.model.McVersion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PaperVersionListParserShould {
    private static final String API_RESPONSE =
            "{\"project_id\":\"paper\",\"project_name\":\"Paper\","
          + "\"version_groups\":[\"1.19\",\"1.20\"],"
          + "\"versions\":[\"1.8.8\",\"1.19.4\",\"1.20\",\"1.20.4\"]}";

    @Test
    public void listTheVersionsNewestFirst() {
        assertEquals(
                List.of(McVersion.of("1.20.4"), McVersion.of("1.20"),
                        McVersion.of("1.19.4"), McVersion.of("1.8.8")),
                PaperVersionListParser.parse(API_RESPONSE));
    }

    @Test
    public void ignoreTheVersionGroupsField() {
        // version_groups repeats "1.19"/"1.20"; reading the whole document instead of the
        // "versions" array would still work here, but only by accident
        assertEquals(4, PaperVersionListParser.parse(API_RESPONSE).size());
    }

    @Test
    public void returnNothingForEmptyOrUnrelatedInput() {
        assertTrue(PaperVersionListParser.parse("").isEmpty());
        assertTrue(PaperVersionListParser.parse(null).isEmpty());
        assertTrue(PaperVersionListParser.parse("{\"error\":\"not found\"}").isEmpty());
    }
}
