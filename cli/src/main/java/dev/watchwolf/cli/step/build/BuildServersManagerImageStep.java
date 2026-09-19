package dev.watchwolf.cli.step.build;

import dev.watchwolf.cli.docker.ComposeProject;
import dev.watchwolf.cli.io.JarInspector;
import dev.watchwolf.cli.proc.CommandResult;
import dev.watchwolf.cli.step.*;

import java.util.Set;

/**
 * Fetches the published ServersManager jar and builds its Docker image.
 *
 * <p>The jar comes from the GitHub releases API, exactly as {@code ci/release/build.sh} does it --
 * but without needing {@code wget} and {@code jq} on the host.
 */
public final class BuildServersManagerImageStep implements Step {
    public static final StepId ID = StepId.of("build-servers-manager-image");

    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/rogermiranda1000/WatchWolf-ServersManager/releases/latest";
    private static final long MINIMUM_JAR_BYTES = 1024 * 1024;

    @Override public StepId id()    { return ID; }
    @Override public String title() { return "Build the ServersManager image"; }

    @Override
    public Set<StepId> requires() {
        return Set.of(CreateRuntimeDirsStep.ID);
    }

    @Override
    public boolean isApplicable(StepContext context) {
        return context.plan().buildServersManagerImage();
    }

    @Override
    public void perform(StepContext context) throws StepFailedException {
        if (!context.files().exists(context.layout().serversManagerJar())) {
            String downloadUrl = this.findReleaseJarUrl(context);
            context.http().download(downloadUrl, context.layout().serversManagerJar(),
                    context.progress());
        }

        ComposeProject compose = new ComposeProject(context.layout(), context.commands(),
                context.interfaces());
        CommandResult result = compose.build(true, context.progress());
        if (!result.succeeded()) {
            throw new StepFailedException("building the ServersManager image",
                    result.failureText(),
                    "Check the Dockerfile in " + context.layout().serversManagerRuntime()
                            + " and that the daemon can reach docker.io for eclipse-temurin:17-jdk.");
        }
    }

    private String findReleaseJarUrl(StepContext context) throws StepFailedException {
        String json;
        try {
            json = context.http().getString(LATEST_RELEASE_URL, context.progress());
        } catch (RuntimeException ex) {
            throw new StepFailedException("looking up the latest ServersManager release",
                    ex.getMessage(),
                    "Check this machine can reach api.github.com. GitHub also rate-limits "
                            + "unauthenticated requests; try again in a few minutes.");
        }

        // "browser_download_url": "...jar"
        var matcher = java.util.regex.Pattern
                .compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.jar)\"").matcher(json);
        if (!matcher.find()) {
            throw new StepFailedException("looking up the latest ServersManager release",
                    "the release carries no .jar asset",
                    "Download ServersManager.jar by hand into "
                            + context.layout().serversManagerRuntime() + ".");
        }
        return matcher.group(1);
    }

    @Override
    public Verification verification() {
        return new Verification() {
            @Override
            public String describe() {
                // the exact image name depends on the compose project, which depends on the
                // runtime flavour; describe() has no context, so it stays generic here
                return "ServersManager.jar is a valid jar and the <flavour>-servers-manager image "
                        + "exists";
            }

            @Override
            public void check(StepContext context) throws VerificationFailedException {
                JarInspector.Result jar = new JarInspector(context.files())
                        .inspectServerJar(context.layout().serversManagerJar(), MINIMUM_JAR_BYTES);
                if (!jar.valid()) {
                    throw new VerificationFailedException(
                            "ServersManager.jar is missing or unusable",
                            jar.problem(),
                            "Run 'watchwolf build' to download it again.");
                }

                String image = context.layout().flavor().serversManagerImage();
                if (!context.docker().imageExists(image)) {
                    throw new VerificationFailedException(
                            "the ServersManager image was not built",
                            "no image named " + image,
                            "Run 'watchwolf build'. If an inherited COMPOSE_PROJECT_NAME is set in "
                                    + "your shell, unset it -- it renames this image.");
                }
            }
        };
    }
}
