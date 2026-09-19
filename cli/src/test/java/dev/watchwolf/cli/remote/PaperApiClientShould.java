package dev.watchwolf.cli.remote;

import dev.watchwolf.cli.fake.FakeHttpFetcher;
import dev.watchwolf.cli.model.McVersion;
import dev.watchwolf.cli.progress.ProgressSink;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class PaperApiClientShould {
    private static final String BUILDS_URL =
            PaperApiClient.PROJECT_URL + "/versions/1.21.11/builds";

    /**
     * The real shape of fill.papermc.io/v3/projects/paper/versions/<version>/builds -- an array of
     * build objects, <b>newest first</b>, each carrying its own channel and download URL. Trimmed
     * to the fields the client actually reads.
     */
    private static String buildsJson(String... idsChannelsUrls) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < idsChannelsUrls.length; i += 3) {
            if (i > 0) json.append(',');
            json.append("{\"id\":").append(idsChannelsUrls[i])
                    .append(",\"time\":\"2026-01-01T00:00:00Z\"")
                    .append(",\"channel\":\"").append(idsChannelsUrls[i + 1]).append('"')
                    .append(",\"commits\":[]")
                    .append(",\"downloads\":{\"server:default\":{\"name\":\"paper.jar\",")
                    .append("\"checksums\":{\"sha256\":\"abc\"},\"size\":1,")
                    .append("\"url\":\"").append(idsChannelsUrls[i + 2]).append("\"}}}");
        }
        return json.append(']').toString();
    }

    @Test
    public void returnTheNewestStableBuildSkippingNewerNonStableOnes() {
        // 132 is the newest overall but still BETA; 131 is ALPHA; 130 is the newest STABLE one --
        // must be picked over the two newer, not-yet-stable builds ahead of it
        String json = buildsJson(
                "132", "BETA", "https://fill-data.papermc.io/132.jar",
                "131", "ALPHA", "https://fill-data.papermc.io/131.jar",
                "130", "STABLE", "https://fill-data.papermc.io/130.jar",
                "129", "STABLE", "https://fill-data.papermc.io/129.jar");
        FakeHttpFetcher http = new FakeHttpFetcher().respondTo(BUILDS_URL, json);

        Optional<PaperApiClient.Download> download =
                new PaperApiClient(http).latestBuild(McVersion.of("1.21.11"), ProgressSink.discarding());

        assertTrue(download.isPresent());
        assertEquals(130, download.get().buildNumber());
        assertEquals("https://fill-data.papermc.io/130.jar", download.get().url());
    }

    @Test
    public void returnEmptyWhenNoBuildIsStableYet() {
        String json = buildsJson(
                "5", "BETA", "https://fill-data.papermc.io/5.jar",
                "4", "ALPHA", "https://fill-data.papermc.io/4.jar");
        FakeHttpFetcher http = new FakeHttpFetcher().respondTo(BUILDS_URL, json);

        Optional<PaperApiClient.Download> download =
                new PaperApiClient(http).latestBuild(McVersion.of("1.21.11"), ProgressSink.discarding());

        assertTrue(download.isEmpty());
    }

    @Test
    public void returnEmptyForAnEmptyBuildsList() {
        FakeHttpFetcher http = new FakeHttpFetcher().respondTo(BUILDS_URL, "[]");

        Optional<PaperApiClient.Download> download =
                new PaperApiClient(http).latestBuild(McVersion.of("1.21.11"), ProgressSink.discarding());

        assertTrue(download.isEmpty());
    }
}
