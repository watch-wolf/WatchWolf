package dev.watchwolf.cli.step.build;

import dev.watchwolf.cli.io.JarInspector;
import dev.watchwolf.cli.model.BuildPlan;
import dev.watchwolf.cli.model.UsualPluginJar;
import dev.watchwolf.cli.remote.WatchWolfWebClient;
import dev.watchwolf.cli.step.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Downloads the "usual plugins" every test server gets.
 *
 * <p>The verification is stricter than it looks, on purpose. It applies the ServersManager's
 * <b>own</b> filename regex, because a name that regex rejects is one the runtime silently ignores
 * -- and "the plugin just isn't there" is a much worse thing to debug than a failed install. It
 * also opens each jar and looks for {@code plugin.yml}: a spigotmc.org resource page saved as a
 * {@code .jar} is a perfectly valid file that fails only when a server tries to load it.
 *
 * <p>This is plan 2's "channel 3" automation: the naming convention moves from a human {@code cp}
 * to a check.
 */
public final class DownloadUsualPluginsStep implements Step {
    public static final StepId ID = StepId.of("download-usual-plugins");

    @Override public StepId id()    { return ID; }
    @Override public String title() { return "Download the usual plugins"; }

    @Override
    public Set<StepId> requires() {
        return Set.of(CreateRuntimeDirsStep.ID);
    }

    @Override
    public boolean isApplicable(StepContext context) {
        // Unresolved (the flags-only path, which never fetches the list up front) means "download
        // everything" -- the same default as before per-plugin selection existed. A resolved but
        // empty set means the menu explicitly deselected every plugin, so there is nothing to do.
        BuildPlan plan = context.plan();
        return !plan.usualPluginsSelectionResolved() || !plan.selectedUsualPlugins().isEmpty();
    }

    @Override
    public void perform(StepContext context) throws StepFailedException {
        WatchWolfWebClient web = new WatchWolfWebClient(context.http());

        List<WatchWolfWebClient.UsualPlugin> plugins;
        try {
            plugins = web.usualPlugins(context.progress());
        } catch (RuntimeException ex) {
            throw new StepFailedException("fetching the usual-plugins list",
                    ex.getMessage(),
                    "Check this machine can reach watchwolf.dev. To install without them, open "
                            + "'Usual plugins' in 'watchwolf build' and press F9 to deselect all.");
        }

        if (plugins.isEmpty()) {
            throw new StepFailedException("fetching the usual-plugins list",
                    "watchwolf.dev returned no plugins",
                    "The API may have changed shape. Report this at "
                            + "https://github.com/watch-wolf/WatchWolf/issues.");
        }

        boolean filterToSelection = context.plan().usualPluginsSelectionResolved();
        Set<String> selected = context.plan().selectedUsualPlugins();

        List<String> failures = new ArrayList<>();
        for (WatchWolfWebClient.UsualPlugin plugin : plugins) {
            String fileName = plugin.fileName();
            if (filterToSelection && !selected.contains(fileName)) continue;   // not picked in the menu

            // fail early rather than write a name the ServersManager cannot parse
            if (!UsualPluginJar.isValidName(fileName)) {
                failures.add(fileName + ": the ServersManager cannot parse this name");
                continue;
            }

            Path destination = context.layout().usualPlugins().resolve(fileName);
            if (context.files().exists(destination)) {
                context.progress().detail(fileName + " is already downloaded");
                continue;
            }

            try {
                context.http().download(web.resolveDownloadUrl(plugin.url()),
                        destination, context.progress());
            } catch (RuntimeException ex) {
                failures.add(fileName + ": " + ex.getMessage());
            }
        }

        if (!failures.isEmpty()) {
            throw new StepFailedException("downloading the usual plugins",
                    String.join("; ", failures),
                    "Re-run 'watchwolf build'; plugins already downloaded are kept.");
        }
    }

    @Override
    public Verification verification() {
        return new Verification() {
            @Override
            public String describe() {
                return "usual-plugins/ holds at least one plugin, every filename parses under the "
                        + "ServersManager's own regex, and every jar contains a plugin.yml";
            }

            @Override
            public void check(StepContext context) throws VerificationFailedException {
                List<Path> jars = context.files().list(context.layout().usualPlugins()).stream()
                        .filter(path -> path.getFileName().toString().endsWith(".jar")).toList();

                if (jars.isEmpty()) {
                    throw new VerificationFailedException(
                            "usual-plugins/ is empty",
                            "no .jar files in " + context.layout().usualPlugins(),
                            "Run 'watchwolf build'. The ServersManager requires at least the "
                                    + "WatchWolf-Server jar here.");
                }

                JarInspector inspector = new JarInspector(context.files());
                List<String> problems = new ArrayList<>();
                for (Path jar : jars) {
                    String name = jar.getFileName().toString();
                    if (!UsualPluginJar.isValidName(name)) {
                        problems.add(name + ": the ServersManager cannot parse this name, so it "
                                + "would silently ignore the plugin");
                        continue;
                    }
                    JarInspector.Result result = inspector.inspectPluginJar(jar);
                    if (!result.valid()) problems.add(name + ": " + result.problem());
                }
                if (!problems.isEmpty()) {
                    throw new VerificationFailedException(
                            "some usual plugins are unusable",
                            String.join("; ", problems),
                            "Delete the offending files from " + context.layout().usualPlugins()
                                    + " and run 'watchwolf build' again.");
                }
            }
        };
    }
}
