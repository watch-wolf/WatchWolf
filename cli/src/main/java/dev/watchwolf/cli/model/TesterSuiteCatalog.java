package dev.watchwolf.cli.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The WatchWolf-Tester integration suites {@code doctor}'s tier 2 can run.
 *
 * <p><b>Hardcoded on purpose.</b> The list has to exist before the Tester is cloned -- the menu
 * offers it during {@code build} -- so it cannot be discovered from the checkout. The safety net is
 * {@code CloneTesterStep}, which verifies every name here resolves to a real file; an upstream
 * rename therefore fails at build time, naming the suite, instead of three minutes into a run.
 */
public final class TesterSuiteCatalog {

    public record Suite(String className, String description, boolean defaultSelected,
                        boolean needsBots) { }

    private static final List<Suite> SUITES = List.of(
            new Suite("ITServerStarterShould",
                    "start and stop a server, no bots", true, false),
            new Suite("ITWorldInteractionPetitionsShould",
                    "place and read a block", false, true),
            new Suite("ITClientsItemModuleShould",
                    "spawns a real bot", false, true),
            new Suite("ITBlocksTester",
                    "broad block coverage -- slow", false, true),
            new Suite("ITItemsTester",
                    "broad item coverage -- slow", false, true),
            new Suite("ITEntitiesTester",
                    "needs with-mobs.yaml -- slow", false, true),
            new Suite("ITWorldGuardPetitionsShould",
                    "needs the WorldGuard usual-plugins", false, false),
            new Suite("ITPluginDownloaderShould",
                    "downloads a plugin into a server", false, false),
            new Suite("ITWorldLoaderShould",
                    "loads a world from a zip", false, false));

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

    /** The {@code --tests} pattern handed to {@code ci/tests.sh}. */
    public static String testPatternFor(Set<String> suites) {
        return String.join(",", suites);
    }
}
