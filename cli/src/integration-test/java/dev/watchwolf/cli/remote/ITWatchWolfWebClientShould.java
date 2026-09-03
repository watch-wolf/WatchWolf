package dev.watchwolf.cli.remote;

import dev.watchwolf.cli.progress.ProgressSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * watchwolf.dev is an external dependency with no fixed content to canary against (unlike Spigot's
 * and Paper's version lists, which are stable and worth hardcoding -- see
 * {@code ITSpigotHubClientShould}/{@code ITPaperApiClientShould}), so this only proves the service
 * is up and answering something real, not an exact list.
 */
@Timeout(value = 1, unit = TimeUnit.MINUTES)
public class ITWatchWolfWebClientShould {
    private final WatchWolfWebClient client = new WatchWolfWebClient(new JdkHttpFetcher());

    @Test
    void listAtLeastOneUsualPlugin() {
        List<WatchWolfWebClient.UsualPlugin> plugins =
                this.client.usualPlugins(ProgressSink.discarding());

        assertFalse(plugins.isEmpty(), "watchwolf.dev/api/v1/usual-plugins returned nothing -- "
                + "either the service is down or the response shape changed under "
                + "WatchWolfWebClient.PLUGIN_ENTRY");
        for (WatchWolfWebClient.UsualPlugin plugin : plugins) {
            assertNotNull(plugin.fileName(), plugin + " could not build a filename");
        }
    }

    @Test
    void findAPublishedWatchWolfServerJar() {
        Optional<WatchWolfWebClient.PublishedServerJar> highest =
                this.client.highestServerJar(ProgressSink.discarding());

        assertTrue(highest.isPresent(), "watchwolf.dev/versions listed no WatchWolf-*.jar -- "
                + "either the service is down or the response shape changed under "
                + "WatchWolfWebClient.PUBLISHED_SERVER_JAR");
        assertTrue(highest.get().fileName().startsWith("WatchWolf-"));
    }
}
