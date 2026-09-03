package dev.watchwolf.cli.remote;

import dev.watchwolf.cli.model.McVersion;
import dev.watchwolf.cli.parse.PaperVersionListParser;
import dev.watchwolf.cli.progress.ProgressSink;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The PaperMC API. Paper ships prebuilt jars, so unlike Spigot there is no build container --
 * these are downloads.
 */
public final class PaperApiClient {
    public static final String PROJECT_URL = "https://api.papermc.io/v2/projects/paper/";

    private static final Pattern LAST_BUILD = Pattern.compile("\"build\"\\s*:\\s*(\\d+)");
    private static final Pattern APPLICATION_NAME =
            Pattern.compile("\"application\"\\s*:\\s*\\{[^}]*\"name\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpFetcher http;

    public PaperApiClient(HttpFetcher http) {
        this.http = http;
    }

    public List<McVersion> availableVersions(ProgressSink progress) {
        progress.begin("Polling Paper versions from api.papermc.io");
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

    /** The newest build's download URL for one version. */
    public Optional<Download> latestBuild(McVersion version, ProgressSink progress) {
        String buildsUrl = PROJECT_URL + "versions/" + version + "/builds/";
        String json = this.http.getString(buildsUrl, progress);

        // the last entries in the document are the newest build
        Matcher buildMatcher = LAST_BUILD.matcher(json);
        String build = null;
        while (buildMatcher.find()) build = buildMatcher.group(1);

        Matcher nameMatcher = APPLICATION_NAME.matcher(json);
        String fileName = null;
        while (nameMatcher.find()) fileName = nameMatcher.group(1);

        if (build == null || fileName == null) return Optional.empty();
        return Optional.of(new Download(
                buildsUrl + build + "/downloads/" + fileName, Integer.parseInt(build)));
    }

    public record Download(String url, int buildNumber) { }
}
