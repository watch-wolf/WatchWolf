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
 * A deliberate canary, not a resilience test -- see {@link ITSpigotHubClientShould}'s Javadoc for
 * why this hardcodes an exact list rather than a loose shape check. This one guards
 * {@link PaperVersionListParser} against fill.papermc.io/v3/projects/paper drifting out from under
 * it (a real risk here: this is the second API this client has had, after api.papermc.io/v2 was
 * retired at the end of 2025).
 */
@Timeout(value = 1, unit = TimeUnit.MINUTES)
public class ITPaperApiClientShould {
    /** fill.papermc.io/v3/projects/paper, {@link McVersion#MIN_SUPPORTED} and above, newest first. */
    private static final List<String> EXPECTED_VERSIONS = List.of(
            "26.2", "26.1.2", "26.1.1", "1.21.11", "1.21.10", "1.21.9", "1.21.8", "1.21.7",
            "1.21.6", "1.21.5", "1.21.4", "1.21.3", "1.21.1", "1.21", "1.20.6", "1.20.5", "1.20.4",
            "1.20.2", "1.20.1", "1.20", "1.19.4", "1.19.3", "1.19.2", "1.19.1", "1.19", "1.18.2",
            "1.18.1", "1.18", "1.17.1", "1.17", "1.16.5", "1.16.4", "1.16.3", "1.16.2", "1.16.1",
            "1.15.2", "1.15.1", "1.15", "1.14.4", "1.14.3", "1.14.2", "1.14.1", "1.14", "1.13.2",
            "1.13.1", "1.13", "1.12.2", "1.12.1", "1.12", "1.11.2", "1.10.2", "1.9.4", "1.8.8");

    @Test
    void listExactlyTheExpectedVersions() {
        List<McVersion> actual =
                new PaperApiClient(new JdkHttpFetcher()).availableVersions(ProgressSink.discarding());

        List<String> asStrings = actual.stream().map(McVersion::toString).collect(Collectors.toList());
        assertEquals(EXPECTED_VERSIONS, asStrings,
                "fill.papermc.io's version list changed -- if that's real (a version was added, "
                        + "removed, or renumbered), update EXPECTED_VERSIONS above; if not, "
                        + "PaperVersionListParser regressed");
    }
}
