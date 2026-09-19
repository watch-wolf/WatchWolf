package dev.watchwolf.cli.remote;

import dev.watchwolf.cli.model.McVersion;
import dev.watchwolf.cli.parse.SpigotVersionListParser;
import dev.watchwolf.cli.progress.ProgressSink;

import java.util.List;

/** The buildable Spigot versions, from {@code hub.spigotmc.org/versions/}. */
public final class SpigotHubClient {
    public static final String VERSIONS_URL = "https://hub.spigotmc.org/versions/";

    private final HttpFetcher http;

    public SpigotHubClient(HttpFetcher http) {
        this.http = http;
    }

    public List<McVersion> availableVersions(ProgressSink progress) {
        // named so a stall says which host it is waiting on, not just "polling"
        progress.begin("Polling Spigot versions from hub.spigotmc.org");
        try {
            List<McVersion> versions =
                    SpigotVersionListParser.parse(this.http.getString(VERSIONS_URL, progress));
            progress.end(versions.size() + " versions");
            return versions;
        } catch (RuntimeException ex) {
            progress.end("failed");
            throw ex;
        }
    }
}
