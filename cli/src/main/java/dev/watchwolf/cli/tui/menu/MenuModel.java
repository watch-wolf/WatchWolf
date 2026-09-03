package dev.watchwolf.cli.tui.menu;

import dev.watchwolf.cli.model.BuildPlan;
import dev.watchwolf.cli.model.McVersion;
import dev.watchwolf.cli.model.TesterSuiteCatalog;
import dev.watchwolf.cli.remote.WatchWolfWebClient;
import dev.watchwolf.cli.tui.Async;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The menuconfig screen's state: a {@link BuildPlan} editor with no terminal code in it.
 *
 * <p>Everything interesting -- toggling, bulk select, the constraint that unticking the Tester
 * clone disables the self-test, the four states of a version list still being fetched -- lives
 * here and is unit-tested without a terminal. {@code MenuConfigScreen} only paints this and turns
 * keys into calls.
 *
 * <p><b>Bulk selection is a keybind, not a row.</b> There is no {@code < All >} pseudo-entry
 * anywhere: those read as options and get mis-clicked. {@code F8} selects all and {@code F9}
 * deselects all, scoped to the focused list, and the screen prints that hint in the list's own
 * footer.
 */
public final class MenuModel {
    public static final String ID_INSTALL_PATH = "install-path";
    public static final String ID_BRANCH_MASTER = "branch-master";
    public static final String ID_BRANCH_DEV = "branch-dev";
    public static final String ID_THREADS = "threads";
    public static final String ID_CLONE_SERVERS_MANAGER = "clone-servers-manager";
    public static final String ID_CLONE_CLIENTS_MANAGER = "clone-clients-manager";
    public static final String ID_CLONE_TESTER = "clone-tester";
    public static final String ID_PULL_IMAGES = "pull-images";
    public static final String ID_SERVER_JARS = "server-jars";
    public static final String ID_SPIGOT = "spigot";
    public static final String ID_PAPER = "paper";
    public static final String ID_USUAL_PLUGINS = "usual-plugins";
    public static final String ID_WATCHWOLF_SERVER = "watchwolf-server";
    public static final String ID_BUILD_IMAGES = "build-images";
    public static final String ID_STARTUP = "startup";
    public static final String ID_SELF_TEST = "self-test";

    private final MenuNode root;
    private Async<List<McVersion>> spigotVersions = Async.notStarted();
    private Async<List<McVersion>> paperVersions = Async.notStarted();
    private Async<List<WatchWolfWebClient.UsualPlugin>> usualPlugins = Async.notStarted();
    private Set<McVersion> spigotInstalled = Set.of();
    private Set<McVersion> paperInstalled = Set.of();

    public MenuModel(BuildPlan initial, String basePath) {
        this.root = MenuNode.submenu("root", "watchwolf build");

        this.root.add(MenuNode.text(ID_INSTALL_PATH, "Install base", basePath)
                .withHelp("Everything is installed under here: the ServersManager and "
                        + "ClientsManager clones, the server jars, the plugins and the logs."));

        boolean dev = "dev".equals(initial.branch());
        MenuNode branchMaster = MenuNode.radio(ID_BRANCH_MASTER, "master", "branch", !dev)
                .withHelp("The released branch of every WatchWolf repository.");
        // not selectable here yet -- 'master' is not in a state this CLI can rely on. Shown
        // rather than hidden, so it's clear the choice exists and why it can't be made right now;
        // still reachable as an explicit 'watchwolf build --branch master' if you know you want it.
        branchMaster.disable("not supported yet -- use --branch master to override");
        this.root.add(branchMaster);
        this.root.add(MenuNode.radio(ID_BRANCH_DEV, "dev", "branch", dev)
                .withHelp("The development branch. Every WatchWolf repo integrates from 'dev'."));

        this.root.add(MenuNode.text(ID_THREADS, "Parallel Spigot builders",
                        String.valueOf(initial.parallelBuilders()))
                .withHelp("BuildTools is slow and mostly single-threaded, so several containers "
                        + "at once help a lot. Each needs about 1.5GB free."));

        this.root.add(MenuNode.check(ID_CLONE_SERVERS_MANAGER,
                        "Clone/update WatchWolf-ServersManager", initial.cloneServersManager())
                .withHelp("Provides Minecraft servers on demand. Required."));
        this.root.add(MenuNode.check(ID_CLONE_CLIENTS_MANAGER,
                        "Clone/update WatchWolf-Client (ClientsManager)",
                        initial.cloneClientsManager())
                .withHelp("Starts the bots that join those servers. Required for any test with "
                        + "players."));
        this.root.add(MenuNode.check(ID_CLONE_TESTER,
                        "Clone/update WatchWolf-Tester", initial.cloneTester())
                .withAnnotation("needed by the self-diagnosis")
                .withHelp("The JUnit harness. The end-to-end self-test runs its suites, so "
                        + "unticking this disables that test."));

        this.root.add(MenuNode.check(ID_PULL_IMAGES,
                        "Pull the JDK images (temurin 8, 16, 17, 21)", initial.pullJdkImages())
                .withHelp("Minecraft picks its Java version by release: 8 before 1.17, 16 for "
                        + "1.17, 17 up to 1.20.4, and 21 from 1.20.5. Without these, the first "
                        + "server of a version pulls at run time, which looks like a hang."));

        MenuNode serverJars = MenuNode.submenu(ID_SERVER_JARS, "Server jars")
                .withHelp("Which Minecraft servers to install. Each Spigot version is compiled by "
                        + "BuildTools in its own container and takes about an hour; Paper ships "
                        + "prebuilt and only has to be downloaded.");
        serverJars.add(MenuNode.submenu(ID_SPIGOT, "Spigot")
                .withHelp("Fetched from hub.spigotmc.org. Each version is compiled by BuildTools "
                        + "in its own container and takes about an hour."));
        serverJars.add(MenuNode.submenu(ID_PAPER, "Paper")
                .withHelp("Fetched from fill.papermc.io. Ships prebuilt, so each version is just a "
                        + "download."));
        this.root.add(serverJars);

        this.root.add(MenuNode.submenu(ID_USUAL_PLUGINS, "Usual plugins (WorldGuard, EssentialsX, ...)")
                .withHelp("Fetched from watchwolf.dev, all selected by default. Tests can name "
                        + "any of them in their config."));
        this.root.add(MenuNode.check(ID_WATCHWOLF_SERVER,
                        "WatchWolf-Server plugin (newest published)",
                        initial.downloadWatchWolfServer())
                .withHelp("The in-game half of WatchWolf. Without it servers start, but no test "
                        + "can talk to them."));
        this.root.add(MenuNode.check(ID_BUILD_IMAGES,
                        "Build the ServersManager and ClientsManager images",
                        initial.buildServersManagerImage())
                .withHelp("Docker images for the two managers."));
        this.root.add(MenuNode.check(ID_STARTUP,
                        "Start WatchWolf at boot", initial.registerStartup())
                .withHelp("Writes a systemd unit, or a Startup .bat under WSL. These touch host "
                        + "state, so the commands are shown for you to approve before they run."));

        MenuNode selfTest = MenuNode.submenu(ID_SELF_TEST, "Run the self-diagnosis when finished")
                .withHelp("Runs WatchWolf-Tester's own integration suites against the environment "
                        + "you just installed. Real servers start, so this takes minutes.");
        for (TesterSuiteCatalog.Suite suite : TesterSuiteCatalog.all()) {
            selfTest.add(MenuNode
                    .check(suite.className(), suite.className(),
                            initial.selfTestSuites().contains(suite.className()))
                    .withAnnotation(suite.description()));
        }
        this.root.add(selfTest);

        this.applyConstraints();
    }

    public MenuNode root() {
        return this.root;
    }

    public Optional<MenuNode> node(String id) {
        return this.root.find(id);
    }

    // ---- editing ---------------------------------------------------------------------------

    /** Space. Radios turn their group off first; disabled rows ignore the key. */
    public void toggle(String id) {
        MenuNode node = this.root.find(id).orElse(null);
        if (node == null || !node.isEnabled()) return;

        if (node.kind() == MenuNode.Kind.RADIO) {
            this.forEach(this.root, other -> {
                if (other.kind() == MenuNode.Kind.RADIO
                        && node.radioGroup().equals(other.radioGroup())) {
                    other.setChecked(false);
                }
            });
            node.setChecked(true);
        } else if (node.kind() == MenuNode.Kind.CHECK) {
            node.setChecked(!node.isChecked());
        }
        this.applyConstraints();
    }

    public void setValue(String id, String value) {
        this.root.find(id).ifPresent(node -> node.setValue(value));
        this.applyConstraints();
    }

    /** F8 on the focused list. Scoped to that list, never the whole screen. */
    public void selectAll(String parentId) {
        this.setAllUnder(parentId, true);
    }

    /** F9 on the focused list. */
    public void deselectAll(String parentId) {
        this.setAllUnder(parentId, false);
    }

    private void setAllUnder(String parentId, boolean checked) {
        MenuNode parent = this.root.find(parentId).orElse(null);
        if (parent == null) return;
        for (MenuNode child : parent.children()) {
            if (child.kind() == MenuNode.Kind.CHECK && child.isEnabled()) {
                child.setChecked(checked);
            }
        }
        this.applyConstraints();
    }

    /**
     * Keeps the screen honest about what it can actually do.
     *
     * <p>The one that matters: without the Tester checkout there is nothing to run the self-test
     * suites from, so that submenu is greyed out <em>with the reason shown</em> rather than
     * silently accepting a selection that would be dropped later.
     */
    public void applyConstraints() {
        boolean tester = this.isChecked(ID_CLONE_TESTER);
        this.node(ID_SELF_TEST).ifPresent(selfTest -> {
            if (tester) {
                selfTest.enable();
                selfTest.setAnnotation(null);
                selfTest.children().forEach(MenuNode::enable);
            } else {
                selfTest.disable("needs WatchWolf-Tester");
                selfTest.setAnnotation("needs WatchWolf-Tester");
                selfTest.children().forEach(
                        child -> child.disable("needs WatchWolf-Tester"));
            }
        });

        this.node(ID_SERVER_JARS).ifPresent(serverJars -> {
            boolean anySelected = !this.selectedVersions(ID_SPIGOT).isEmpty()
                    || !this.selectedVersions(ID_PAPER).isEmpty();
            serverJars.setAnnotation(anySelected ? null : "nothing selected");
        });
        this.annotateIfNothingSelected(ID_SPIGOT);
        this.annotateIfNothingSelected(ID_PAPER);
        this.annotateIfNothingSelected(ID_USUAL_PLUGINS);
    }

    /**
     * Marks a fetched, per-item checkbox submenu (Spigot/Paper versions, usual plugins) with
     * "nothing selected" once it has something to select and none of it is checked. Silent while
     * the list is still empty -- not fetched yet, still loading, or the fetch failed -- so a
     * network problem shows as the status line's own failure message, not a misleading blanket
     * "nothing selected" that reads like a user mistake.
     */
    private void annotateIfNothingSelected(String submenuId) {
        this.node(submenuId).ifPresent(submenu -> {
            boolean fetched = !submenu.children().isEmpty();
            boolean anySelected = submenu.children().stream().anyMatch(MenuNode::isChecked);
            submenu.setAnnotation(fetched && !anySelected ? "nothing selected" : null);
        });
    }

    public boolean isChecked(String id) {
        return this.root.find(id).map(MenuNode::isChecked).orElse(false);
    }

    // ---- the async version lists -----------------------------------------------------------

    public Async<List<McVersion>> spigotVersions() { return this.spigotVersions; }
    public Async<List<McVersion>> paperVersions()  { return this.paperVersions; }

    public void spigotLoading(Instant startedAt) {
        this.spigotVersions = Async.loading(startedAt);
        // whatever is already on disk stays selectable and visible while the network is still
        // being waited on, not just once it answers -- see populateInstalledOnly's Javadoc
        this.populateInstalledOnly(ID_SPIGOT, "spigot", this.spigotInstalled);
    }

    public void paperLoading(Instant startedAt) {
        this.paperVersions = Async.loading(startedAt);
        this.populateInstalledOnly(ID_PAPER, "paper", this.paperInstalled);
    }

    /** Every fetched version starts selected, except ones already built/downloaded -- there is
     *  nothing to do for those, so preselecting them would just mean rebuilding what is already
     *  there the moment someone presses 's' without looking. */
    public void spigotLoaded(List<McVersion> versions) {
        this.spigotVersions = Async.loaded(versions);
        this.populateVersions(ID_SPIGOT, "spigot", versions, this.spigotInstalled);
        this.applyConstraints();
    }

    public void paperLoaded(List<McVersion> versions) {
        this.paperVersions = Async.loaded(versions);
        this.populateVersions(ID_PAPER, "paper", versions, this.paperInstalled);
        this.applyConstraints();
    }

    /**
     * hub.spigotmc.org (or fill.papermc.io) is unreachable, times out, or returns garbage --
     * whatever is already on disk must stay exactly as selectable as it was while loading, which
     * is also what the failure's own remedy text promises ("versions already on disk are still
     * selectable"). Without this the submenu would go from "local versions, none checked" (while
     * loading) to completely empty the moment the fetch fails, silently making that promise false
     * and leaving nothing to select even for a version already built.
     */
    public void spigotFailed(String detail, String remedy) {
        this.spigotVersions = Async.failed(detail, remedy);
        this.populateInstalledOnly(ID_SPIGOT, "spigot", this.spigotInstalled);
    }

    public void paperFailed(String detail, String remedy) {
        this.paperVersions = Async.failed(detail, remedy);
        this.populateInstalledOnly(ID_PAPER, "paper", this.paperInstalled);
    }

    public Async<List<WatchWolfWebClient.UsualPlugin>> usualPlugins() { return this.usualPlugins; }

    public void usualPluginsLoading(Instant startedAt) {
        this.usualPlugins = Async.loading(startedAt);
    }

    /** Every plugin starts selected -- "download the usual plugins" means all of them by default. */
    public void usualPluginsLoaded(List<WatchWolfWebClient.UsualPlugin> plugins) {
        this.usualPlugins = Async.loaded(plugins);
        MenuNode parent = this.root.find(ID_USUAL_PLUGINS).orElse(null);
        if (parent != null) {
            parent.children().clear();
            for (WatchWolfWebClient.UsualPlugin plugin : plugins) {
                parent.add(MenuNode.check(plugin.fileName(),
                        plugin.name() + " " + plugin.version(), true));
            }
        }
        this.applyConstraints();
    }

    public void usualPluginsFailed(String detail, String remedy) {
        this.usualPlugins = Async.failed(detail, remedy);
    }

    /**
     * The checked plugins' file names, or {@code null} while the list is still not-started,
     * loading, or failed -- {@link BuildPlan#selectedUsualPlugins()} treats {@code null} as
     * "unresolved, download everything" rather than "nothing was fetched to select from".
     */
    public Set<String> selectedUsualPluginsOrNullIfUnresolved() {
        if (!this.usualPlugins.isLoaded()) return null;
        MenuNode parent = this.root.find(ID_USUAL_PLUGINS).orElse(null);
        if (parent == null) return null;

        Set<String> selected = new LinkedHashSet<>();
        for (MenuNode child : parent.children()) {
            if (child.kind() == MenuNode.Kind.CHECK && child.isChecked()) selected.add(child.id());
        }
        return selected;
    }

    /** Versions already on disk: listed immediately, so the pane is useful before the network is. */
    public void withInstalled(Set<McVersion> spigot, Set<McVersion> paper) {
        this.spigotInstalled = Set.copyOf(spigot);
        this.paperInstalled = Set.copyOf(paper);
    }

    private void populateVersions(String parentId, String prefix, List<McVersion> versions,
                                  Set<McVersion> installed) {
        MenuNode parent = this.root.find(parentId).orElse(null);
        if (parent == null) return;
        parent.children().clear();

        for (McVersion version : versions) {
            boolean alreadyInstalled = installed.contains(version);
            MenuNode node =
                    MenuNode.check(prefix + ":" + version, version.toString(), !alreadyInstalled);
            if (alreadyInstalled) node.setAnnotation("installed");
            parent.add(node);
        }
    }

    /**
     * The degraded version of {@link #populateVersions}, used while the remote list is loading or
     * once it has failed: only what {@link #withInstalled} already knows is on disk, none of it
     * preselected (there is no "newest" to preselect without the remote index), each annotated
     * "installed" the same way a successful fetch would mark it.
     */
    private void populateInstalledOnly(String parentId, String prefix, Set<McVersion> installed) {
        MenuNode parent = this.root.find(parentId).orElse(null);
        if (parent == null) return;
        parent.children().clear();

        for (McVersion version : installed) {
            MenuNode node = MenuNode.check(prefix + ":" + version, version.toString(), false);
            node.setAnnotation("installed");
            parent.add(node);
        }
        this.applyConstraints();
    }

    public List<McVersion> selectedVersions(String parentId) {
        MenuNode parent = this.root.find(parentId).orElse(null);
        if (parent == null) return List.of();

        List<McVersion> selected = new ArrayList<>();
        for (MenuNode child : parent.children()) {
            if (child.kind() != MenuNode.Kind.CHECK || !child.isChecked()) continue;
            McVersion version = McVersion.parseOrNull(child.label());
            if (version != null) selected.add(version);
        }
        return selected;
    }

    // ---- the result ------------------------------------------------------------------------

    public BuildPlan toBuildPlan() {
        Set<String> suites = new LinkedHashSet<>();
        this.node(ID_SELF_TEST).ifPresent(selfTest -> {
            for (MenuNode child : selfTest.children()) {
                if (child.isChecked()) suites.add(child.id());
            }
        });

        return BuildPlan.builder()
                .branch(this.isChecked(ID_BRANCH_DEV) ? "dev" : "master")
                .parallelBuilders(parseIntOr(this.node(ID_THREADS)
                        .map(MenuNode::value).orElse("1"), 1))
                .cloneServersManager(this.isChecked(ID_CLONE_SERVERS_MANAGER))
                .cloneClientsManager(this.isChecked(ID_CLONE_CLIENTS_MANAGER))
                .cloneTester(this.isChecked(ID_CLONE_TESTER))
                .pullJdkImages(this.isChecked(ID_PULL_IMAGES))
                // Spigot/Paper have no on/off checkbox of their own any more -- they are submenus
                // of individually selectable versions, so "build Spigot at all" is simply "is
                // anything checked in there"
                .buildSpigot(!this.selectedVersions(ID_SPIGOT).isEmpty())
                .buildPaper(!this.selectedVersions(ID_PAPER).isEmpty())
                .spigotVersions(this.selectedVersions(ID_SPIGOT))
                .paperVersions(this.selectedVersions(ID_PAPER))
                .selectedUsualPlugins(this.selectedUsualPluginsOrNullIfUnresolved())
                .downloadWatchWolfServer(this.isChecked(ID_WATCHWOLF_SERVER))
                .buildServersManagerImage(this.isChecked(ID_BUILD_IMAGES))
                .buildClientsManagerImage(this.isChecked(ID_BUILD_IMAGES))
                .registerStartup(this.isChecked(ID_STARTUP))
                .registerLauncher(this.isChecked(ID_STARTUP))
                .runSelfTest(!suites.isEmpty())
                .selfTestSuites(suites)
                .build();
    }

    public String installPath() {
        return this.node(ID_INSTALL_PATH).map(MenuNode::value).orElse(null);
    }

    private void forEach(MenuNode node, java.util.function.Consumer<MenuNode> action) {
        action.accept(node);
        for (MenuNode child : node.children()) this.forEach(child, action);
    }

    private static int parseIntOr(String text, int fallback) {
        try {
            return Integer.parseInt(text.strip());
        } catch (RuntimeException ex) {
            return fallback;
        }
    }
}
