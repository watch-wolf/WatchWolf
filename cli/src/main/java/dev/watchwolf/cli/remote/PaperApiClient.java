package dev.watchwolf.cli.remote;

import dev.watchwolf.cli.model.McVersion;
import dev.watchwolf.cli.parse.PaperVersionListParser;
import dev.watchwolf.cli.progress.ProgressSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The PaperMC "Fill" downloads service. Paper ships prebuilt jars, so unlike Spigot there is no
 * build container -- these are downloads.
 *
 * <p>Replaces the old {@code api.papermc.io/v2} client: that API stopped receiving builds at the
 * end of 2025 in favour of {@code fill.papermc.io/v3}. The builds endpoint's shape changed too --
 * {@code GET .../versions/<version>/builds} now returns a JSON array of build objects, <b>newest
 * first</b> (the old API's were oldest-first), each carrying a {@code "channel"}
 * ({@code STABLE}/{@code BETA}/{@code ALPHA}/...) and its download under
 * {@code downloads."server:default".url} -- not a {@code build}/{@code application.name} pair
 * assembled into a URL by hand.
 */
public final class PaperApiClient {
    public static final String PROJECT_URL = "https://fill.papermc.io/v3/projects/paper";

    private static final String STABLE_CHANNEL = "STABLE";
    private static final Pattern CHANNEL = Pattern.compile("\"channel\"\\s*:\\s*\"([A-Z]+)\"");
    // the only "url" field in this document is downloads."server:default".url, so matching every
    // occurrence in order lines up 1:1 with CHANNEL's matches -- see PaperApiClientShould for the
    // build-by-build proof this holds against a real response, not just this one endpoint's shape
    private static final Pattern DOWNLOAD_URL = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern BUILD_ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    private final HttpFetcher http;

    public PaperApiClient(HttpFetcher http) {
        this.http = http;
    }

    public List<McVersion> availableVersions(ProgressSink progress) {
        progress.begin("Polling Paper versions from fill.papermc.io");
        try {
            List<McVersion> versions =
                    PaperVersionListParser.parse(this.http.getString(PROJECT_URL, progress));
            progress.end(versions.size() + " versions");
            return versions;
        } catch (RuntimeException ex) {
            progress.end("failed");
            throw ex;
        }
    }

    /** The newest {@code STABLE} build's download URL for one version. */
    public Optional<Download> latestBuild(McVersion version, ProgressSink progress) {
        String buildsUrl = PROJECT_URL + "/versions/" + version + "/builds";
        String json = this.http.getString(buildsUrl, progress);

        List<String> channels = allMatches(CHANNEL, json);
        List<String> urls = allMatches(DOWNLOAD_URL, json);
        List<String> ids = allMatches(BUILD_ID, json);

        // newest first: the first STABLE entry IS the newest stable build, no reordering needed
        for (int i = 0; i < channels.size() && i < urls.size() && i < ids.size(); i++) {
            if (STABLE_CHANNEL.equals(channels.get(i))) {
                return Optional.of(new Download(urls.get(i), Integer.parseInt(ids.get(i))));
            }
        }
        return Optional.empty();
    }

    private static List<String> allMatches(Pattern pattern, String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) found.add(matcher.group(1));
        return found;
    }

    public record Download(String url, int buildNumber) { }
}
