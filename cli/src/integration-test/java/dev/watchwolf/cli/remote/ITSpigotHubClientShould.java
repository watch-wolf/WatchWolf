package dev.watchwolf.cli.remote;

import dev.watchwolf.cli.model.McVersion;
import dev.watchwolf.cli.progress.ProgressSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A deliberate canary, not a resilience test: this hardcodes the exact version list
 * hub.spigotmc.org/versions/ carried on the day it was written and asserts it byte-for-byte, so
 * that if Spigot ever removes a version, adds one, or (as already happened once) changes its
 * numbering scheme, {@link SpigotVersionListParser} silently drifting out of sync fails loudly here
 * instead of quietly shipping a wrong or incomplete version picker. When this fails because the
 * real list legitimately changed, update the hardcoded list below -- do not loosen the assertion.
 */
@Timeout(value = 1, unit = TimeUnit.MINUTES)
public class ITSpigotHubClientShould {
    /** hub.spigotmc.org/versions/, {@link McVersion#MIN_SUPPORTED} and above, newest first. */
    private static final List<String> EXPECTED_VERSIONS = List.of(
            "26.2", "26.1.2", "26.1.1", "26.1", "1.21.11", "1.21.10", "1.21.9", "1.21.8", "1.21.7",
            "1.21.6", "1.21.5", "1.21.4", "1.21.3", "1.21.2", "1.21.1", "1.21", "1.20.6", "1.20.5",
            "1.20.4", "1.20.3", "1.20.2", "1.20.1", "1.20", "1.19.4", "1.19.3", "1.19.2", "1.19.1",
            "1.19", "1.18.2", "1.18.1", "1.18", "1.17.1", "1.17", "1.16.5", "1.16.4", "1.16.3",
            "1.16.2", "1.16.1", "1.15.2", "1.15.1", "1.15", "1.14.4", "1.14.3", "1.14.2", "1.14.1",
            "1.14", "1.13.2", "1.13.1", "1.13", "1.12.2", "1.12.1", "1.12", "1.11.2", "1.11.1",
            "1.11", "1.10.2", "1.10", "1.9.4", "1.9.2", "1.9", "1.8.8", "1.8.7", "1.8.6", "1.8.5",
            "1.8.4", "1.8.3", "1.8");

    @Test
    void listExactlyTheExpectedVersions() {
        List<McVersion> actual =
                new SpigotHubClient(new JdkHttpFetcher()).availableVersions(ProgressSink.discarding());

        List<String> asStrings = actual.stream().map(McVersion::toString).collect(Collectors.toList());
        assertEquals(EXPECTED_VERSIONS, asStrings,
                "hub.spigotmc.org's version list changed -- if that's real (a version was added, "
                        + "removed, or renumbered), update EXPECTED_VERSIONS above; if not, "
                        + "SpigotVersionListParser regressed");
    }
}
