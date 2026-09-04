package dev.watchwolf.cli.validation;

import dev.watchwolf.cli.model.ServerTypeVersion;
import dev.watchwolf.cli.model.TesterSuiteCatalog;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TesterSuiteCatalog} hardcodes the servers each self-test suite starts, transcribed from
 * WatchWolf-Tester's own YAML configs -- it has to, because the menu offers the suites before the
 * Tester is ever cloned. This is the safety net: when a sibling Tester checkout is reachable, the
 * configs are parsed and any drift fails here, naming the suite.
 *
 * <p>Without it the menu would keep locking last year's versions: the build would look right, and
 * the self-test would fail an hour later on a server nobody installed.
 *
 * <p><b>Skips when the checkout is not reachable</b>, exactly as
 * {@link MinecraftJavaVersionsMatchesCoreShould} does -- {@code ci/tests.sh --validation} mounts
 * only {@code cli/}, so this is a check for a developer with the full monorepo, not a CI gate.
 */
public class TesterSuiteServersMatchTheirConfigsShould {

    private static final List<Path> CANDIDATE_ROOTS = List.of(
            Paths.get("../../WatchWolf-Tester"),      // cli/ -> WatchWolf/ -> monorepo root
            Paths.get("../WatchWolf-Tester"));        // beside the WatchWolf repo

    /** Which config each suite reads -- its own {@code getConfigFile()}, relative to the Tester. */
    private static final Map<String, String> CONFIG_OF_SUITE = Map.of(
            "ITServerStarterShould", "src/integration-test/java/server_starter/resources/basic.yaml",
            "ITWorldInteractionPetitionsShould", "src/integration-test/java/generic/resources/config.yaml",
            "ITClientsItemModuleShould", "src/integration-test/java/client/resources/config.yaml",
            "ITBlocksTester", "src/integration-test/java/generic/resources/config.yaml",
            "ITItemsTester", "src/integration-test/java/generic/resources/config.yaml",
            "ITEntitiesTester", "src/integration-test/java/generic/resources/with-mobs.yaml",
            "ITWorldGuardPetitionsShould", "src/integration-test/java/worldguard/resources/config.yaml",
            "ITPluginDownloaderShould", "src/integration-test/java/plugin_downloader/resources/config.yaml",
            "ITWorldLoaderShould", "src/integration-test/java/world/resources/config.yaml");

    /** {@code  - Spigot:} — a server type heading inside the {@code server-type} block. */
    private static final Pattern TYPE_LINE = Pattern.compile("^\\s*-\\s*(\\w+):\\s*$");
    /** {@code      - "1.8.8"} — one version under the current heading. Commented ones do not count. */
    private static final Pattern VERSION_LINE = Pattern.compile("^\\s*-\\s*\"([\\d.]+)\"\\s*$");

    /**
     * One {@code @Test} rather than a {@code @TestFactory}, for the reason
     * {@link MinecraftJavaVersionsMatchesCoreShould} spells out: a factory that aborts reports
     * "0 tests run", which reads as coverage rather than as the deliberate skip it is.
     */
    @Test
    void matchTheYamlConfigEachSuiteReads() throws IOException {
        Path tester = CANDIDATE_ROOTS.stream().filter(Files::isDirectory).findFirst().orElse(null);
        Assumptions.assumeTrue(tester != null,
                "No sibling WatchWolf-Tester checkout found (checked " + CANDIDATE_ROOTS
                        + "); skipping the drift check. This is expected when only cli/ is "
                        + "mounted, as ci/tests.sh does.");

        List<String> problems = new ArrayList<>();
        for (TesterSuiteCatalog.Suite suite : TesterSuiteCatalog.all()) {
            problems.addAll(problemsWith(tester, suite));
        }
        if (!problems.isEmpty()) {
            throw new AssertionError("The menu locks the jars named in TesterSuiteCatalog, so this "
                    + "drift would install the wrong servers:\n  " + String.join("\n  ", problems));
        }
    }

    /** The parser itself, proven on a sample -- this half runs everywhere, checkout or not. */
    @Test
    void readAServerTypeBlockIgnoringCommentedOutVersions() {
        Set<ServerTypeVersion> parsed = parseServers(List.of(
                "server-type:",
                "  - Spigot:",
                "      - \"1.19\"",
                "#      - \"1.18.1\"",
                "      - \"1.13\" # first API 7 version",
                "  - Paper:",
                "      - \"1.14\"",
                "",
                "users:",
                "  - \"MinecraftGamer_Z\"",
                "plugin: \"WatchWolf\""));

        assertEquals(Set.of(ServerTypeVersion.of("Spigot", "1.19"),
                        ServerTypeVersion.of("Spigot", "1.13"),
                        ServerTypeVersion.of("Paper", "1.14")), parsed,
                "a commented-out version is a server the suite does NOT start");
    }

    private static List<String> problemsWith(Path tester, TesterSuiteCatalog.Suite suite)
            throws IOException {
        String config = CONFIG_OF_SUITE.get(suite.className());
        if (config == null) {
            return List.of(suite.className() + " is in TesterSuiteCatalog but this check does not "
                    + "know which config it reads; add it to CONFIG_OF_SUITE (see the suite's own "
                    + "getConfigFile()).");
        }

        Path path = tester.resolve(config);
        if (!Files.exists(path)) {
            return List.of(suite.className() + " reads " + config + ", which no longer exists in "
                    + "the Tester checkout.");
        }

        Set<ServerTypeVersion> declared = new LinkedHashSet<>(suite.starts());
        Set<ServerTypeVersion> actual = parseServers(Files.readAllLines(path));
        if (declared.equals(actual)) return List.of();

        return List.of(suite.className() + ": " + config + " says " + actual
                + ", the catalog says " + declared);
    }

    /** The {@code server-type:} block, ignoring commented-out versions (the files are full of them). */
    private static Set<ServerTypeVersion> parseServers(List<String> lines) {
        Set<ServerTypeVersion> servers = new LinkedHashSet<>();
        Map<String, List<String>> byType = new LinkedHashMap<>();

        boolean inBlock = false;
        String type = null;
        for (String raw : lines) {
            String line = raw.split("#", 2)[0];      // strip comments; a commented version is off
            if (line.isBlank()) continue;

            if (line.stripTrailing().equals("server-type:")) {
                inBlock = true;
                continue;
            }
            if (!inBlock) continue;
            if (!line.startsWith(" ") && !line.startsWith("-")) break;   // the block ended

            Matcher typeMatch = TYPE_LINE.matcher(line);
            if (typeMatch.matches()) {
                type = typeMatch.group(1);
                byType.computeIfAbsent(type, ignored -> new ArrayList<>());
                continue;
            }
            Matcher versionMatch = VERSION_LINE.matcher(line);
            if (versionMatch.matches() && type != null) {
                byType.get(type).add(versionMatch.group(1));
            }
        }

        byType.forEach((serverType, versions) ->
                versions.forEach(version -> servers.add(ServerTypeVersion.of(serverType, version))));
        return servers;
    }
}
