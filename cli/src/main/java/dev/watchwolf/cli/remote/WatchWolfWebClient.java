package dev.watchwolf.cli.remote;

import dev.watchwolf.cli.model.McVersion;
import dev.watchwolf.cli.model.UsualPluginJar;
import dev.watchwolf.cli.progress.ProgressSink;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * watchwolf.dev: the "usual plugins" list and the published WatchWolf-Server jars.
 *
 * <p>Spigot-hosted plugins are resolved through Spiget exactly the way the ServersManager does it,
 * so a plugin the installer accepts is one the runtime can also fetch.
 */
public final class WatchWolfWebClient {
    public static final String USUAL_PLUGINS_URL = "https://watchwolf.dev/api/v1/usual-plugins";
    public static final String VERSIONS_URL = "https://watchwolf.dev/versions";

    /** Same extraction as {@code ServersManagerPluginDeserializer.getSpigotDownloadUrl}. */
    private static final Pattern SPIGOT_RESOURCE_ID =
            Pattern.compile("spigotmc\\.org/resources/(?:[^/]*?)(\\d+)(?:/|$)");

    private static final Pattern PLUGIN_ENTRY = Pattern.compile(
            "\\{[^{}]*?\"name\"\\s*:\\s*\"(?<name>[^\"]+)\"[^{}]*?"
          + "\"version\"\\s*:\\s*\"(?<version>[^\"]+)\"[^{}]*?"
          + "\"min_mc_version\"\\s*:\\s*\"(?<min>[^\"]+)\"[^{}]*?"
          + "\"max_mc_version\"\\s*:\\s*\"(?<max>[^\"]+)\"[^{}]*?"
          + "\"url\"\\s*:\\s*\"(?<url>[^\"]+)\"[^{}]*?}",
            Pattern.DOTALL);

    /** {@code WatchWolf-<version>-<minMc>-<maxMc>.jar} as published on the versions page. */
    private static final Pattern PUBLISHED_SERVER_JAR =
            Pattern.compile("WatchWolf-([\\d.]+)-([\\d.]+)-((?:[\\d.]+)|LATEST)\\.jar");

    private final HttpFetcher http;

    public WatchWolfWebClient(HttpFetcher http) {
        this.http = http;
    }

    public record UsualPlugin(String name, String version, McVersion minMcVersion,
                              McVersion maxMcVersion, String url) {
        public String fileName() {
            return UsualPluginJar.fileNameFor(this.name, this.version,
                    this.minMcVersion, this.maxMcVersion);
        }
    }

    public List<UsualPlugin> usualPlugins(ProgressSink progress) {
        progress.begin("Fetching the usual-plugins list from watchwolf.dev");
        String json;
        try {
            json = this.http.getString(USUAL_PLUGINS_URL, progress);
        } catch (RuntimeException ex) {
            progress.end("failed");
            throw ex;
        }

        List<UsualPlugin> plugins = new ArrayList<>();
        Matcher matcher = PLUGIN_ENTRY.matcher(json);
        while (matcher.find()) {
            McVersion min = McVersion.parseOrNull(matcher.group("min"));
            McVersion max = McVersion.parseOrNull(matcher.group("max"));
            if (min == null || max == null) continue;
            plugins.add(new UsualPlugin(matcher.group("name"), matcher.group("version"),
                    min, max, matcher.group("url")));
        }
        progress.end(plugins.size() + " plugins");
        return plugins;
    }

    /**
     * Turns a plugin's declared URL into one that actually serves a jar.
     *
     * <p>A spigotmc.org resource page is HTML, not a download; the ServersManager rewrites those
     * through Spiget, and so do we -- otherwise the installer saves an HTML page as a {@code .jar}
     * and the failure only surfaces when a server refuses to start.
     */
    public String resolveDownloadUrl(String declaredUrl) {
        Matcher matcher = SPIGOT_RESOURCE_ID.matcher(declaredUrl);
        if (matcher.find()) {
            return "https://api.spiget.org/v2/resources/" + matcher.group(1) + "/download";
        }
        return declaredUrl;
    }

    /** The highest {@code WatchWolf-*.jar} published on watchwolf.dev/versions. */
    public Optional<PublishedServerJar> highestServerJar(ProgressSink progress) {
        progress.begin("Looking up the newest WatchWolf-Server from watchwolf.dev");
        String html;
        try {
            html = this.http.getString(VERSIONS_URL, progress);
        } catch (RuntimeException ex) {
            progress.end("failed");
            throw ex;
        }

        List<PublishedServerJar> found = new ArrayList<>();
        Matcher matcher = PUBLISHED_SERVER_JAR.matcher(html);
        while (matcher.find()) {
            McVersion min = McVersion.parseOrNull(matcher.group(2));
            McVersion max = McVersion.parseOrNull(matcher.group(3));
            if (min == null || max == null) continue;
            found.add(new PublishedServerJar(matcher.group(0), matcher.group(1), min, max));
        }

        Optional<PublishedServerJar> highest = found.stream()
                .max(Comparator.comparing(jar -> McVersion.parseOrNull(jar.pluginVersion()),
                        Comparator.nullsFirst(Comparator.naturalOrder())));
        progress.end(highest.map(PublishedServerJar::fileName).orElse("none found"));
        return highest;
    }

    public record PublishedServerJar(String fileName, String pluginVersion,
                                     McVersion minMcVersion, McVersion maxMcVersion) {
        public String url() {
            return VERSIONS_URL + "/" + this.fileName;
        }
    }
}
