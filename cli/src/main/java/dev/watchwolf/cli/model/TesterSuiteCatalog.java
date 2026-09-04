package dev.watchwolf.cli.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The WatchWolf-Tester integration suites {@code doctor}'s tier 2 can run.
 *
 * <p><b>Hardcoded on purpose.</b> The list has to exist before the Tester is cloned -- the menu
 * offers it during {@code build} -- so it cannot be discovered from the checkout. The safety net is
 * {@code CloneTesterStep}, which verifies every name here resolves to a real file; an upstream
 * rename therefore fails at build time, naming the suite, instead of three minutes into a run.
 *
 * <p>Each suite also declares <b>the servers it starts</b>. Those are not a guess: every suite
 * reads a YAML config naming its {@code server-type} versions, and the lists below are transcribed
 * from those files (named per suite so the next person can check them):
 *
 * <pre>
 *   ITServerStarterShould              server_starter/resources/basic.yaml
 *   ITWorldInteractionPetitionsShould  generic/resources/config.yaml
 *   ITClientsItemModuleShould          client/resources/config.yaml
 *   ITBlocksTester                     generic/resources/config.yaml
 *   ITItemsTester                      generic/resources/config.yaml
 *   ITEntitiesTester                   generic/resources/with-mobs.yaml
 *   ITWorldGuardPetitionsShould        worldguard/resources/config.yaml
 *   ITPluginDownloaderShould           plugin_downloader/resources/config.yaml
 *   ITWorldLoaderShould                world/resources/config.yaml
 * </pre>
 *
 * <p>The menu uses this to lock the jars a ticked suite needs: selecting a self-test that starts
 * Spigot 1.8.8 and then not installing Spigot 1.8.8 is a run that can only fail, minutes in, for a
 * reason that was knowable before it started.
 */
public final class TesterSuiteCatalog {

    public record Suite(String className, String description, boolean defaultSelected,
                        boolean needsBots, List<ServerTypeVersion> starts) {
        public Suite {
            starts = List.copyOf(starts);
        }
    }

    private static final List<Suite> SUITES = List.of(
            new Suite("ITServerStarterShould",
                    "start and stop a server, no bots", true, false,
                    List.of(spigot("1.8.8"), spigot("1.12.2"), spigot("1.15"), spigot("1.20.2"),
                            paper("1.8.8"), paper("1.12.2"), paper("1.15"), paper("1.20.2"))),
            new Suite("ITWorldInteractionPetitionsShould",
                    "place and read a block", false, true,
                    List.of(spigot("1.19"), spigot("1.8.8"), paper("1.14"))),
            new Suite("ITClientsItemModuleShould",
                    "spawns a real bot", false, true,
                    List.of(spigot("1.18"), spigot("1.8.8"))),
            new Suite("ITBlocksTester",
                    "broad block coverage -- slow", false, true,
                    List.of(spigot("1.19"), spigot("1.8.8"), paper("1.14"))),
            new Suite("ITItemsTester",
                    "broad item coverage -- slow", false, true,
                    List.of(spigot("1.19"), spigot("1.8.8"), paper("1.14"))),
            new Suite("ITEntitiesTester",
                    "needs with-mobs.yaml -- slow", false, true,
                    List.of(spigot("1.19"))),
            new Suite("ITWorldGuardPetitionsShould",
                    "needs the WorldGuard usual-plugins", false, false,
                    List.of(spigot("1.19"), spigot("1.13"), spigot("1.12.2"), spigot("1.8.8"))),
            new Suite("ITPluginDownloaderShould",
                    "downloads a plugin into a server", false, false,
                    List.of(spigot("1.20.2"))),
            new Suite("ITWorldLoaderShould",
                    "loads a world from a zip", false, false,
                    List.of(spigot("1.8.8"))));

    private static ServerTypeVersion spigot(String version) {
        return ServerTypeVersion.of("Spigot", version);
    }

    private static ServerTypeVersion paper(String version) {
        return ServerTypeVersion.of("Paper", version);
    }

    private TesterSuiteCatalog() {
    }

    public static List<Suite> all() {
        return SUITES;
    }

    /** One cheap suite: enough to prove the environment end to end without an hour of runtime. */
    public static Set<String> defaultSelection() {
        Set<String> selected = new LinkedHashSet<>();
        for (Suite suite : SUITES) {
            if (suite.defaultSelected()) selected.add(suite.className());
        }
        return selected;
    }

    public static Set<String> allClassNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Suite suite : SUITES) names.add(suite.className());
        return names;
    }

    public static Optional<Suite> byClassName(String className) {
        return SUITES.stream().filter(suite -> suite.className().equals(className)).findFirst();
    }

    /**
     * Every server the given suites will try to start, deduplicated -- what {@code build} has to
     * have installed for them to pass.
     */
    public static Set<ServerTypeVersion> serversNeededBy(Set<String> suites) {
        Set<ServerTypeVersion> needed = new LinkedHashSet<>();
        for (Suite suite : SUITES) {
            if (suites.contains(suite.className())) needed.addAll(suite.starts());
        }
        return needed;
    }

    /** Which of the given suites starts {@code server} -- the "why" of a locked menu row. */
    public static List<String> suitesNeeding(Set<String> suites, ServerTypeVersion server) {
        List<String> needing = new ArrayList<>();
        for (Suite suite : SUITES) {
            if (suites.contains(suite.className()) && suite.starts().contains(server)) {
                needing.add(suite.className());
            }
        }
        return needing;
    }

    /** The {@code --tests} pattern handed to {@code ci/tests.sh}. */
    public static String testPatternFor(Set<String> suites) {
        return String.join(",", suites);
    }
}
