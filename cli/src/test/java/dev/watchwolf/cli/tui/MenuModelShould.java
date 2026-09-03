package dev.watchwolf.cli.tui;

import dev.watchwolf.cli.model.BuildPlan;
import dev.watchwolf.cli.model.McVersion;
import dev.watchwolf.cli.remote.WatchWolfWebClient;
import dev.watchwolf.cli.tui.menu.MenuModel;
import dev.watchwolf.cli.tui.menu.MenuNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class MenuModelShould {
    private MenuModel menu;

    @BeforeEach
    void setUp() {
        this.menu = new MenuModel(BuildPlan.defaults(), "/home/someone/WatchWolf");
    }

    private static WatchWolfWebClient.UsualPlugin plugin(String name) {
        return new WatchWolfWebClient.UsualPlugin(name, "1.0",
                McVersion.of("1.8"), McVersion.of("LATEST"), "https://example.invalid/" + name);
    }

    @Test
    public void toggleACheckboxWithSpace() {
        assertTrue(this.menu.isChecked(MenuModel.ID_PULL_IMAGES));
        this.menu.toggle(MenuModel.ID_PULL_IMAGES);
        assertFalse(this.menu.isChecked(MenuModel.ID_PULL_IMAGES));
        this.menu.toggle(MenuModel.ID_PULL_IMAGES);
        assertTrue(this.menu.isChecked(MenuModel.ID_PULL_IMAGES));
    }

    @Test
    public void keepOnlyOneRadioOfAGroupSelected() {
        this.menu.toggle(MenuModel.ID_BRANCH_DEV);
        assertTrue(this.menu.isChecked(MenuModel.ID_BRANCH_DEV));
        assertFalse(this.menu.isChecked(MenuModel.ID_BRANCH_MASTER));
        assertEquals("dev", this.menu.toBuildPlan().branch());
    }

    @Test
    public void defaultToDevAndKeepMasterVisibleButUnselectable() {
        // master is not in a state this CLI can rely on yet -- shown so the choice is visible
        // (never hidden), but toggling it must be a no-op
        assertEquals("dev", this.menu.toBuildPlan().branch());

        MenuNode master = this.menu.node(MenuModel.ID_BRANCH_MASTER).orElseThrow();
        assertFalse(master.isEnabled());
        assertTrue(master.disabledReason().isPresent());

        this.menu.toggle(MenuModel.ID_BRANCH_MASTER);

        assertFalse(this.menu.isChecked(MenuModel.ID_BRANCH_MASTER));
        assertTrue(this.menu.isChecked(MenuModel.ID_BRANCH_DEV));
        assertEquals("dev", this.menu.toBuildPlan().branch());
    }

    @Test
    public void disableTheSelfTestWhenTheTesterIsNotCloned() {
        // the suites can only run from a Tester checkout, so the menu must not accept a
        // selection it would silently drop later
        this.menu.toggle(MenuModel.ID_CLONE_TESTER);

        MenuNode selfTest = this.menu.node(MenuModel.ID_SELF_TEST).orElseThrow();
        assertFalse(selfTest.isEnabled());
        assertEquals("needs WatchWolf-Tester", selfTest.disabledReason().orElseThrow());
        assertTrue(selfTest.children().stream().noneMatch(MenuNode::isChecked));
        assertFalse(this.menu.toBuildPlan().runSelfTest());
    }

    @Test
    public void reEnableTheSelfTestWhenTheTesterComesBack() {
        this.menu.toggle(MenuModel.ID_CLONE_TESTER);
        this.menu.toggle(MenuModel.ID_CLONE_TESTER);

        assertTrue(this.menu.node(MenuModel.ID_SELF_TEST).orElseThrow().isEnabled());
    }

    @Test
    public void ignoreASpaceOnADisabledRow() {
        this.menu.toggle(MenuModel.ID_CLONE_TESTER);   // disables the suites

        MenuNode suite = this.menu.node(MenuModel.ID_SELF_TEST).orElseThrow().children().get(0);
        this.menu.toggle(suite.id());

        assertFalse(suite.isChecked());
    }

    @Test
    public void selectAndDeselectEveryRowOfOneListOnly() {
        // F8/F9 are scoped to the focused list -- there is no "< All >" row anywhere, because
        // those read as options and get mis-clicked
        this.menu.spigotLoaded(List.of(McVersion.of("1.20.4"), McVersion.of("1.19.4"),
                McVersion.of("1.8.8")));

        this.menu.selectAll(MenuModel.ID_SPIGOT);
        assertEquals(3, this.menu.selectedVersions(MenuModel.ID_SPIGOT).size());
        assertTrue(this.menu.isChecked(MenuModel.ID_PULL_IMAGES), "other lists are untouched");

        this.menu.deselectAll(MenuModel.ID_SPIGOT);
        assertTrue(this.menu.selectedVersions(MenuModel.ID_SPIGOT).isEmpty());
    }

    @Test
    public void selectEveryFetchedVersionByDefaultExceptAlreadyInstalledOnes() {
        // the install default: everything not already built/downloaded starts selected, so a
        // fresh install picks up every version with one keypress ('s') rather than none
        this.menu.withInstalled(Set.of(McVersion.of("1.8.8")), Set.of());
        this.menu.spigotLoaded(List.of(McVersion.of("1.20.4"), McVersion.of("1.8.8")));

        MenuNode spigot = this.menu.node(MenuModel.ID_SPIGOT).orElseThrow();
        MenuNode installed = spigot.children().stream()
                .filter(child -> child.label().equals("1.8.8")).findFirst().orElseThrow();

        assertEquals("installed", installed.annotation().orElseThrow());
        assertFalse(installed.isChecked(), "no point rebuilding what is already there");
        assertEquals(List.of(McVersion.of("1.20.4")),
                this.menu.selectedVersions(MenuModel.ID_SPIGOT));
    }

    @Test
    public void applyTheSameDefaultToPaperVersions() {
        this.menu.withInstalled(Set.of(), Set.of(McVersion.of("1.20.4")));
        this.menu.paperLoaded(List.of(McVersion.of("1.20.4"), McVersion.of("1.20.6")));

        assertEquals(List.of(McVersion.of("1.20.6")), this.menu.selectedVersions(MenuModel.ID_PAPER));
    }

    @Test
    public void startWithTheVersionListsNotYetFetched() {
        assertEquals(Async.State.NOT_STARTED, this.menu.spigotVersions().state());
        assertEquals(Async.State.NOT_STARTED, this.menu.paperVersions().state());
    }

    @Test
    public void stayUsableWhileAVersionListIsLoading() {
        this.menu.spigotLoading(Instant.now());

        assertTrue(this.menu.spigotVersions().isLoading());
        // the rest of the menu keeps working while the network is busy
        this.menu.toggle(MenuModel.ID_PULL_IMAGES);
        assertFalse(this.menu.isChecked(MenuModel.ID_PULL_IMAGES));
    }

    @Test
    public void keepTheMenuUsableWhenAVersionListFails() {
        this.menu.spigotFailed("hub.spigotmc.org unreachable (timeout after 10s)",
                "Locally present versions are still selectable.");

        assertTrue(this.menu.spigotVersions().hasFailed());
        assertTrue(this.menu.spigotVersions().failureDetail().orElseThrow()
                .contains("hub.spigotmc.org"), "the pane must name the host it waited on");
        assertNotNull(this.menu.toBuildPlan(), "a failed fetch must not break the plan");
    }

    @Test
    public void makeSpigotAndPaperIndividuallySelectableSubmenus() {
        // "Server jars" used to hold two flat on/off checkboxes; each is now its own submenu of
        // individually selectable versions, the same shape as "Usual plugins"
        assertEquals(MenuNode.Kind.SUBMENU,
                this.menu.node(MenuModel.ID_SPIGOT).orElseThrow().kind());
        assertEquals(MenuNode.Kind.SUBMENU,
                this.menu.node(MenuModel.ID_PAPER).orElseThrow().kind());
    }

    @Test
    public void deriveBuildSpigotFromWhetherAnyVersionIsSelected() {
        // there is no separate on/off flag any more -- picking zero versions IS "don't build
        // Spigot", picking one IS "build it"
        this.menu.spigotLoaded(List.of(McVersion.of("1.20.4"), McVersion.of("1.8.8")));
        assertTrue(this.menu.toBuildPlan().buildSpigot(), "everything is selected by default");

        this.menu.deselectAll(MenuModel.ID_SPIGOT);
        assertFalse(this.menu.toBuildPlan().buildSpigot());

        this.menu.toggle("spigot:1.8.8");
        assertTrue(this.menu.toBuildPlan().buildSpigot());
    }

    @Test
    public void keepLocallyInstalledVersionsSelectableWhileTheListIsStillLoading() {
        // withInstalled's own promise ("listed immediately, so the pane is useful before the
        // network is") did not actually hold before: nothing populated the submenu until the
        // fetch succeeded, so the pane was empty for the entire loading window regardless
        this.menu.withInstalled(Set.of(McVersion.of("1.8.8")), Set.of());

        this.menu.spigotLoading(Instant.now());

        MenuNode spigot = this.menu.node(MenuModel.ID_SPIGOT).orElseThrow();
        assertEquals(1, spigot.children().size());
        MenuNode installed = spigot.children().get(0);
        assertEquals("1.8.8", installed.label());
        assertEquals("installed", installed.annotation().orElseThrow());
        assertFalse(installed.isChecked());
    }

    @Test
    public void keepLocallyInstalledVersionsSelectableWhenTheFetchFails() {
        // the failure's own remedy text ("versions already on disk are still selectable") must
        // actually be true -- this is the "hub.spigotmc.org is down" scenario
        this.menu.withInstalled(Set.of(McVersion.of("1.8.8")), Set.of());
        this.menu.spigotLoading(Instant.now());

        this.menu.spigotFailed("hub.spigotmc.org: Connection refused",
                "Versions already on disk are still selectable; pass --skip-spigot-build to skip "
                        + "Spigot entirely.");

        assertTrue(this.menu.spigotVersions().hasFailed());
        MenuNode spigot = this.menu.node(MenuModel.ID_SPIGOT).orElseThrow();
        assertEquals(1, spigot.children().size(), "the version already on disk must still be there");
        assertTrue(spigot.isEnabled(), "the submenu itself must stay reachable, not lock up");

        // and it must actually still be selectable, not just visible
        this.menu.toggle("spigot:1.8.8");
        assertEquals(List.of(McVersion.of("1.8.8")), this.menu.selectedVersions(MenuModel.ID_SPIGOT));
        BuildPlan plan = this.menu.toBuildPlan();
        assertTrue(plan.buildSpigot());
        assertEquals(List.of(McVersion.of("1.8.8")), plan.spigotVersions());
    }

    @Test
    public void leaveTheSubmenuEmptyWithoutCrashingWhenNothingIsInstalledAndTheFetchFails() {
        // the other half of "something goes wrong": no local fallback exists either -- must
        // degrade to an empty, harmless submenu rather than throw or leave stale state around
        this.menu.spigotLoading(Instant.now());
        this.menu.spigotFailed("hub.spigotmc.org: Connection refused", "Try again later.");

        assertTrue(this.menu.spigotVersions().hasFailed());
        assertTrue(this.menu.node(MenuModel.ID_SPIGOT).orElseThrow().children().isEmpty());
        assertFalse(this.menu.toBuildPlan().buildSpigot());
        assertDoesNotThrow(this.menu::toBuildPlan);
    }

    @Test
    public void markSpigotsAggregateStateAsItsSelectionChanges() {
        this.menu.spigotLoaded(List.of(McVersion.of("1.20.4"), McVersion.of("1.8.8")));
        MenuNode spigot = this.menu.node(MenuModel.ID_SPIGOT).orElseThrow();
        assertEquals(MenuNode.AggregateState.ALL, spigot.aggregateState(), "all selected by default");
        assertEquals("[*]", spigot.marker());

        this.menu.toggle("spigot:1.8.8");
        assertEquals(MenuNode.AggregateState.SOME, spigot.aggregateState());
        assertEquals("[o]", spigot.marker());

        this.menu.deselectAll(MenuModel.ID_SPIGOT);
        assertEquals(MenuNode.AggregateState.NONE, spigot.aggregateState());
        assertEquals("[ ]", spigot.marker());
    }

    @Test
    public void rollUpServerJarsAggregateStateAcrossBothSpigotAndPaper() {
        // "Server jars" holds no checkboxes of its own -- Spigot and Paper are themselves
        // submenus -- so its marker must roll up every CHECK descendant at any depth
        this.menu.spigotLoaded(List.of(McVersion.of("1.8.8")));
        this.menu.paperLoaded(List.of(McVersion.of("1.20.4")));
        MenuNode serverJars = this.menu.node(MenuModel.ID_SERVER_JARS).orElseThrow();
        assertEquals(MenuNode.AggregateState.ALL, serverJars.aggregateState());

        this.menu.deselectAll(MenuModel.ID_PAPER);
        assertEquals(MenuNode.AggregateState.SOME, serverJars.aggregateState());

        this.menu.deselectAll(MenuModel.ID_SPIGOT);
        assertEquals(MenuNode.AggregateState.NONE, serverJars.aggregateState());
    }

    @Test
    public void selectEveryUsualPluginByDefaultOnceFetched() {
        this.menu.usualPluginsLoaded(List.of(plugin("WorldGuard"), plugin("EssentialsX")));

        MenuNode usualPlugins = this.menu.node(MenuModel.ID_USUAL_PLUGINS).orElseThrow();
        assertEquals(2, usualPlugins.children().size());
        assertTrue(usualPlugins.children().stream().allMatch(MenuNode::isChecked),
                "usual plugins must default to all selected");
        assertEquals(2, this.menu.toBuildPlan().selectedUsualPlugins().size());
    }

    @Test
    public void deselectOneUsualPluginWithSpace() {
        this.menu.usualPluginsLoaded(List.of(plugin("WorldGuard"), plugin("EssentialsX")));
        MenuNode worldGuard = this.menu.node(MenuModel.ID_USUAL_PLUGINS).orElseThrow()
                .children().get(0);

        this.menu.toggle(worldGuard.id());

        assertFalse(worldGuard.isChecked());
        assertEquals(1, this.menu.toBuildPlan().selectedUsualPlugins().size());
    }

    @Test
    public void selectAndDeselectAllUsualPluginsOnly() {
        this.menu.usualPluginsLoaded(List.of(plugin("WorldGuard"), plugin("EssentialsX")));

        this.menu.deselectAll(MenuModel.ID_USUAL_PLUGINS);
        assertTrue(this.menu.toBuildPlan().selectedUsualPlugins().isEmpty());
        assertTrue(this.menu.isChecked(MenuModel.ID_PULL_IMAGES), "other lists are untouched");

        this.menu.selectAll(MenuModel.ID_USUAL_PLUGINS);
        assertEquals(2, this.menu.toBuildPlan().selectedUsualPlugins().size());
    }

    @Test
    public void respectExplicitlyDeselectingEveryUsualPlugin() {
        // must not be indistinguishable from "never fetched" -- that would silently download
        // everything despite the explicit choice
        this.menu.usualPluginsLoaded(List.of(plugin("WorldGuard")));
        this.menu.deselectAll(MenuModel.ID_USUAL_PLUGINS);

        BuildPlan plan = this.menu.toBuildPlan();
        assertTrue(plan.usualPluginsSelectionResolved());
        assertTrue(plan.selectedUsualPlugins().isEmpty());
    }

    @Test
    public void leaveUsualPluginsUnresolvedUntilTheListLoads() {
        // the step falls back to "download everything" for an unresolved plan -- starting the
        // build before the fetch finishes (or after it failed) must not silently mean "none"
        assertFalse(this.menu.toBuildPlan().usualPluginsSelectionResolved());

        this.menu.usualPluginsLoading(Instant.now());
        assertFalse(this.menu.toBuildPlan().usualPluginsSelectionResolved());

        this.menu.usualPluginsFailed("watchwolf.dev unreachable", "retry later");
        assertFalse(this.menu.toBuildPlan().usualPluginsSelectionResolved());

        this.menu.usualPluginsLoaded(List.of(plugin("WorldGuard")));
        assertTrue(this.menu.toBuildPlan().usualPluginsSelectionResolved());
    }

    @Test
    public void markUsualPluginsAggregateStateWhenNothingIsSelected() {
        this.menu.usualPluginsLoaded(List.of(plugin("WorldGuard")));
        this.menu.deselectAll(MenuModel.ID_USUAL_PLUGINS);

        MenuNode usualPlugins = this.menu.node(MenuModel.ID_USUAL_PLUGINS).orElseThrow();
        assertEquals(MenuNode.AggregateState.NONE, usualPlugins.aggregateState());
        assertEquals("[ ]", usualPlugins.marker());
    }

    @Test
    public void carryEverySelectionIntoTheBuildPlan() {
        this.menu.toggle(MenuModel.ID_BRANCH_DEV);
        this.menu.setValue(MenuModel.ID_THREADS, "4");
        this.menu.paperLoaded(List.of(McVersion.of("1.20.4")));

        BuildPlan plan = this.menu.toBuildPlan();

        assertEquals("dev", plan.branch());
        assertEquals(4, plan.parallelBuilders());
        assertEquals(List.of(McVersion.of("1.20.4")), plan.paperVersions());
    }

    @Test
    public void fallBackToOneBuilderWhenTheThreadCountIsNotANumber() {
        this.menu.setValue(MenuModel.ID_THREADS, "lots");
        assertEquals(1, this.menu.toBuildPlan().parallelBuilders());
    }

    @Test
    public void rejectAnInvalidThreadCountBeforeItReachesTheModel() {
        // MenuConfigScreen is what actually refuses to commit an invalid edit (see
        // MenuConfigScreenTextValidationShould) -- this is the rule it enforces
        MenuNode threads = this.menu.node(MenuModel.ID_THREADS).orElseThrow();

        assertTrue(threads.validate("").isPresent(), "empty must be rejected");
        assertTrue(threads.validate("   ").isPresent(), "blank must be rejected");
        assertTrue(threads.validate("abc").isPresent(), "non-numeric must be rejected");
        assertTrue(threads.validate("0").isPresent(), "zero must be rejected");
        assertTrue(threads.validate("-1").isPresent(), "negative must be rejected");
        assertTrue(threads.validate("4").isEmpty(), "a positive whole number must be accepted");
    }

    @Test
    public void rejectAnEmptyInstallPath() {
        MenuNode installPath = this.menu.node(MenuModel.ID_INSTALL_PATH).orElseThrow();

        assertTrue(installPath.validate("").isPresent());
        assertTrue(installPath.validate("   ").isPresent());
        assertTrue(installPath.validate("/home/someone/WatchWolf").isEmpty());
    }

    @Test
    public void offerEverySuiteInTheCatalog() {
        assertEquals(dev.watchwolf.cli.model.TesterSuiteCatalog.all().size(),
                this.menu.node(MenuModel.ID_SELF_TEST).orElseThrow().children().size());
    }
}
