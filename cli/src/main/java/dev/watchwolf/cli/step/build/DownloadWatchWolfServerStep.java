package dev.watchwolf.cli.step.build;

import dev.watchwolf.cli.inventory.ServerJarInventory;
import dev.watchwolf.cli.io.JarInspector;
import dev.watchwolf.cli.model.McVersion;
import dev.watchwolf.cli.model.UsualPluginJar;
import dev.watchwolf.cli.remote.WatchWolfWebClient;
import dev.watchwolf.cli.step.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Downloads the newest published WatchWolf-Server plugin into {@code usual-plugins/}.
 *
 * <p>Its filename encodes the Minecraft range it supports, so the verification also checks that
 * range actually <b>covers every version in {@code server-types/}</b>. Getting that wrong is only
 * discovered today when a server refuses to start, long after the install.
 */
public final class DownloadWatchWolfServerStep implements Step {
    public static final StepId ID = StepId.of("download-watchwolf-server");

    @Override public StepId id()    { return ID; }
    @Override public String title() { return "Download the WatchWolf-Server plugin"; }

    @Override
    public Set<StepId> requires() {
        return Set.of(CreateRuntimeDirsStep.ID);
    }

    @Override
    public boolean isApplicable(StepContext context) {
        return context.plan().downloadWatchWolfServer();
    }

    @Override
    public void perform(StepContext context) throws StepFailedException {
        WatchWolfWebClient web = new WatchWolfWebClient(context.http());

        Optional<WatchWolfWebClient.PublishedServerJar> newest;
        try {
            newest = web.highestServerJar(context.progress());
        } catch (RuntimeException ex) {
            throw new StepFailedException("looking up the WatchWolf-Server plugin",
                    ex.getMessage(),
                    "Check this machine can reach watchwolf.dev/versions.");
        }

        if (newest.isEmpty()) {
            throw new StepFailedException("looking up the WatchWolf-Server plugin",
                    "watchwolf.dev/versions listed no WatchWolf-*.jar",
                    "Download one by hand into " + context.layout().usualPlugins()
                            + ", named WatchWolf-<version>-<minMc>-<maxMc>.jar.");
        }

        WatchWolfWebClient.PublishedServerJar jar = newest.get();
        Path destination = context.layout().usualPlugins().resolve(jar.fileName());
        if (context.files().exists(destination)) {
            context.progress().detail(jar.fileName() + " is already downloaded");
        } else {
            context.http().download(jar.url(), destination, context.progress());
        }
        context.publish(ID, jar.pluginVersion());
    }

    @Override
    public Verification verification() {
        return new Verification() {
            @Override
            public String describe() {
                return "a WatchWolf-Server jar is in usual-plugins/, and its Minecraft range covers "
                        + "every version in server-types/";
            }

            @Override
            public void check(StepContext context) throws VerificationFailedException {
                List<UsualPluginJar> serverJars = new ArrayList<>();
                for (Path path : context.files().list(context.layout().usualPlugins())) {
                    UsualPluginJar parsed =
                            UsualPluginJar.parseOrNull(path.getFileName().toString());
                    if (parsed != null && parsed.isWatchWolfServer()) serverJars.add(parsed);
                }

                if (serverJars.isEmpty()) {
                    throw new VerificationFailedException(
                            "no WatchWolf-Server plugin is installed",
                            "usual-plugins/ holds no WatchWolf-*.jar",
                            "The ServersManager requires one: without it, servers start but no "
                                    + "test can talk to them. Run 'watchwolf build'.");
                }

                JarInspector inspector = new JarInspector(context.files());
                for (UsualPluginJar jar : serverJars) {
                    JarInspector.Result result = inspector.inspectPluginJar(
                            context.layout().usualPlugins().resolve(jar.fileName()));
                    if (!result.valid()) {
                        throw new VerificationFailedException(
                                "the WatchWolf-Server plugin is unusable",
                                jar.fileName() + ": " + result.problem(),
                                "Delete it and run 'watchwolf build' again.");
                    }
                }

                // the filename's <min>-<max> must cover what is actually installed, or a server of
                // that version starts without the plugin and every test times out
                List<String> uncovered = new ArrayList<>();
                for (McVersion installed : new ServerJarInventory(context.files(),
                        context.layout()).installedVersions()) {
                    boolean covered = serverJars.stream().anyMatch(jar -> jar.supports(installed));
                    if (!covered) uncovered.add(installed.toString());
                }
                if (!uncovered.isEmpty()) {
                    context.progress().warn("The installed WatchWolf-Server plugin does not "
                            + "declare support for Minecraft " + String.join(", ", uncovered)
                            + ". Servers of those versions will start without it, and tests "
                            + "against them will not connect.");
                }
            }
        };
    }
}
