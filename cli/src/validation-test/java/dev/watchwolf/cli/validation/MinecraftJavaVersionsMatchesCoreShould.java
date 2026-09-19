package dev.watchwolf.cli.validation;

import dev.watchwolf.cli.model.JavaImageCatalog;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link JavaImageCatalog} deliberately duplicates
 * {@code dev.watchwolf.core.utils.DockerUtilities.getJavaVersion} -- see that class's Javadoc for
 * why WatchWolf-Core cannot simply be a dependency. This is the safety net: when a sibling
 * WatchWolf-Core checkout is reachable, it parses the real source and fails on drift instead of
 * letting the duplicate rot silently.
 *
 * <p><b>Skips, rather than fails, when the checkout is not reachable</b> -- which is the normal
 * case in this module's own {@code ci/tests.sh --validation}, since that mounts only the {@code cli/}
 * directory and not its siblings. It is a real check for a developer running the full monorepo
 * checkout (this machine's {@code /mnt/raid/.../WatchWolf-Core} among them), not a CI gate; a
 * dynamic {@code @TestFactory} would misrepresent a deliberate skip as "0 checks ran", so this is a
 * single {@code @Test} using {@code Assumptions} instead.
 */
public class MinecraftJavaVersionsMatchesCoreShould {

    /** Candidate locations, checked in order, covering both layouts this could run from. */
    private static final List<Path> CANDIDATE_PATHS = List.of(
            // cli/ -> WatchWolf/ -> the monorepo root -> WatchWolf-Core (this machine's layout)
            Paths.get("../../WatchWolf-Core/src/main/java/dev/watchwolf/core/utils/DockerUtilities.java"),
            // a checkout where WatchWolf-Core sits directly beside the WatchWolf repo
            Paths.get("../WatchWolf-Core/src/main/java/dev/watchwolf/core/utils/DockerUtilities.java"));

    /** e.g. {@code if (result < 0) return 8;} on the line after a {@code roundTo(2)} comparison. */
    private static final Pattern RETURNED_JAVA_VERSION = Pattern.compile("return\\s+(\\d+)\\s*;");
    private static final Pattern COMPARED_VERSION = Pattern.compile("compareTo\\(\"([\\d.]+)\"\\)");

    @Test
    void matchCoresDockerUtilitiesGetJavaVersion() throws IOException {
        Path source = CANDIDATE_PATHS.stream()
                .filter(Files::exists)
                .findFirst()
                .orElse(null);
        Assumptions.assumeTrue(source != null,
                "No sibling WatchWolf-Core checkout found (checked " + CANDIDATE_PATHS
                        + "); skipping the drift check. This is expected when only cli/ is "
                        + "mounted, as ci/tests.sh does.");

        String contents = Files.readString(source);

        // pull every "compareTo("<version>")" -> the boundary versions Core's logic branches on
        List<String> boundaries = COMPARED_VERSION.matcher(contents).results()
                .map(match -> match.group(1)).distinct().toList();
        Assumptions.assumeTrue(!boundaries.isEmpty(),
                "Could not find any version boundary in " + source
                        + " -- its shape has likely changed; update this check by hand rather "
                        + "than trusting a stale regex to pass silently.");

        // and every "return <n>;" -> the Java versions it can return, in source order
        List<Integer> returnedJavaVersions = RETURNED_JAVA_VERSION.matcher(contents).results()
                .map(match -> Integer.parseInt(match.group(1))).toList();

        for (Integer javaVersion : returnedJavaVersions) {
            if (!JavaImageCatalog.ALL_JAVA_VERSIONS.contains(javaVersion)) {
                throw new AssertionError("WatchWolf-Core's DockerUtilities.getJavaVersion returns "
                        + javaVersion + ", which JavaImageCatalog.ALL_JAVA_VERSIONS "
                        + JavaImageCatalog.ALL_JAVA_VERSIONS + " does not list. Update the "
                        + "catalog (and its pulled-image list) to match.");
            }
        }

        // the two known boundaries this module's own tests are pinned to (1.17 and 1.20.5) must
        // still appear in Core's source, or its logic has moved without this catalog following
        for (String expectedBoundary : List.of("1.17", "1.20.5")) {
            if (boundaries.stream().noneMatch(b -> b.equals(expectedBoundary))) {
                throw new AssertionError("WatchWolf-Core's DockerUtilities no longer compares "
                        + "against '" + expectedBoundary + "', but JavaImageCatalog's tests "
                        + "(JavaImageCatalogShould) still assume that boundary. Re-derive "
                        + "JavaImageCatalog.javaVersionFor from the current source of " + source
                        + " and update its tests together.");
            }
        }
    }
}
