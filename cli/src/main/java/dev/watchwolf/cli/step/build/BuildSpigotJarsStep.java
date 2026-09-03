package dev.watchwolf.cli.step.build;

import dev.watchwolf.cli.docker.RunSpec;
import dev.watchwolf.cli.io.JarInspector;
import dev.watchwolf.cli.model.JavaImageCatalog;
import dev.watchwolf.cli.model.McVersion;
import dev.watchwolf.cli.parse.ContainerNames;
import dev.watchwolf.cli.step.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the selected Spigot versions with BuildTools, one detached container each.
 *
 * <p>A Java port of {@code SpigotBuilder.sh}, whose own README asks for exactly this. Porting drops
 * the {@code curl}/{@code jq}/{@code sudo docker} host dependencies -- the point of the whole
 * migration -- and fixes two things the Bash version got wrong:
 *
 * <ul>
 *   <li>Its version list ended in {@code uniq -d}, which prints only <em>duplicated</em> lines, so
 *       any version appearing once on the index was silently undownloadable.</li>
 *   <li>It built straight into {@code server-types/Spigot/}, so a container killed part-way left a
 *       truncated jar that only failed months later at server start. Here each build lands in a
 *       staging directory and is renamed only after the jar is verified.</li>
 * </ul>
 *
 * <p>Minimum jar size is deliberately generous: a real Spigot jar is tens of MB, and anything much
 * smaller is an error page or a partial write.
 */
public final class BuildSpigotJarsStep implements Step {
    public static final StepId ID = StepId.of("build-spigot");

    private static final long MINIMUM_JAR_BYTES = 10L * 1024 * 1024;
    private static final int POLL_SECONDS = 15;
    private static final int MAX_MINUTES_PER_VERSION = 120;

    @Override public StepId id()    { return ID; }
    @Override public String title() { return "Build the Spigot server jars"; }

    @Override
    public Set<StepId> requires() {
        return Set.of(CreateRuntimeDirsStep.ID);
    }

    @Override
    public boolean isApplicable(StepContext context) {
        return context.plan().buildSpigot() && !context.plan().spigotVersions().isEmpty();
    }

    @Override
    public String skipReason(StepContext context) {
        return context.plan().buildSpigot()
                ? "no Spigot versions selected"
                : "Spigot builds were skipped";
    }

    @Override
    public void perform(StepContext context) throws StepFailedException {
        List<McVersion> wanted = this.missingVersions(context);
        if (wanted.isEmpty()) {
            context.progress().detail("every selected Spigot version is already built");
            return;
        }

        Path staging = context.layout().serverTypes("Spigot").resolve(".staging");
        try {
            context.files().createDirectories(staging);
        } catch (IOException ex) {
            throw new StepFailedException("preparing the Spigot staging directory", ex.getMessage(),
                    "Check the permissions on " + context.layout().serverTypes("Spigot") + ".");
        }

        int parallel = context.plan().parallelBuilders();
        context.progress().begin("Building " + wanted.size() + " Spigot version(s) with BuildTools, "
                + parallel + " at a time (this takes about an hour per version)");

        Map<McVersion, String> failures = new LinkedHashMap<>();
        List<McVersion> queue = new ArrayList<>(wanted);
        List<McVersion> running = new ArrayList<>();
        long startedAt = System.nanoTime();

        while (!queue.isEmpty() || !running.isEmpty()) {
            while (!queue.isEmpty() && running.size() < parallel) {
                McVersion version = queue.remove(0);
                this.startBuilder(context, staging, version);
                running.add(version);
            }

            sleepSeconds(POLL_SECONDS);

            List<McVersion> finished = new ArrayList<>();
            for (McVersion version : running) {
                if (this.builderStillRunning(context, version)) continue;
                finished.add(version);

                Path built = staging.resolve(version + ".jar");
                JarInspector.Result inspection =
                        new JarInspector(context.files()).inspectServerJar(built, MINIMUM_JAR_BYTES);
                if (inspection.valid()) {
                    this.promote(context, built, version, failures);
                } else {
                    failures.put(version, inspection.problem() + this.lastLinesOf(context, version));
                }
            }
            running.removeAll(finished);

            long minutes = (System.nanoTime() - startedAt) / 60_000_000_000L;
            context.progress().update((wanted.size() - queue.size() - running.size())
                    + "/" + wanted.size() + " done, " + running.size() + " building, "
                    + minutes + "m elapsed", wanted.size() - queue.size() - running.size(),
                    wanted.size());

            if (minutes > (long) MAX_MINUTES_PER_VERSION * wanted.size()) {
                throw new StepFailedException("building Spigot",
                        "the builders have been running for " + minutes + " minutes",
                        "Check 'docker ps' for Spigot_build_* containers and their logs.");
            }
        }
        context.progress().end("built " + (wanted.size() - failures.size())
                + "/" + wanted.size());

        if (!failures.isEmpty()) {
            StringBuilder detail = new StringBuilder();
            failures.forEach((version, problem) ->
                    detail.append("\n        ").append(version).append(": ").append(problem));
            throw new StepFailedException("building Spigot",
                    failures.size() + " version(s) failed:" + detail,
                    "BuildTools needs network access and about 1.5GB free. Re-run to retry only "
                            + "the versions that failed; the ones that succeeded are kept.");
        }
    }

    private void promote(StepContext context, Path built, McVersion version,
                         Map<McVersion, String> failures) {
        Path destination = context.layout().serverJar("Spigot", version.toString());
        try {
            context.files().createDirectories(destination.getParent());
            java.nio.file.Files.move(built, destination,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            failures.put(version, "built but could not be moved into place: " + ex.getMessage());
        }
    }

    private void startBuilder(StepContext context, Path staging, McVersion version) {
        int javaVersion = JavaImageCatalog.javaVersionFor(version);
        // same shape as SpigotBuilder.sh: a detached container with the output dir at /Versions
        String script = "apt-get update && apt-get install -y git && "
                + "mkdir -p /BuildTools && cd /BuildTools && "
                + "curl -k -z BuildTools.jar -o BuildTools.jar "
                + "https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar && "
                + "java -jar BuildTools.jar --rev " + version + " && "
                + "cp spigot-" + version + ".jar /Versions/" + version + ".jar";

        context.progress().detail("starting " + ContainerNames.spigotBuilderFor(version.toString())
                + " on " + JavaImageCatalog.imageForJavaVersion(javaVersion));

        context.docker().runDetached(RunSpec.of(JavaImageCatalog.imageForJavaVersion(javaVersion))
                .named(ContainerNames.spigotBuilderFor(version.toString()))
                .bind(staging.toString(), "/Versions")
                .withEntrypoint("/bin/bash", "-c")
                .withCommand(script)
                .autoRemove(false));      // keep it so its log can explain a failure
    }

    private boolean builderStillRunning(StepContext context, McVersion version) {
        return context.docker()
                .findContainer(ContainerNames.spigotBuilderFor(version.toString()))
                .map(container -> container.isRunning())
                .orElse(false);
    }

    private String lastLinesOf(StepContext context, McVersion version) {
        try {
            List<String> lines = context.docker()
                    .logs(ContainerNames.spigotBuilderFor(version.toString()), 40);
            if (lines.isEmpty()) return "";
            return "\n          last output: " + lines.get(lines.size() - 1);
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private List<McVersion> missingVersions(StepContext context) {
        List<McVersion> missing = new ArrayList<>();
        for (McVersion version : context.plan().spigotVersions()) {
            Path jar = context.layout().serverJar("Spigot", version.toString());
            if (!context.files().exists(jar)) missing.add(version);
        }
        return missing;
    }

    private static void sleepSeconds(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public Verification verification() {
        return new Verification() {
            @Override
            public String describe() {
                return "every selected Spigot version is a valid jar in server-types/Spigot/, and "
                        + "no build container was left behind";
            }

            @Override
            public void check(StepContext context) throws VerificationFailedException {
                JarInspector inspector = new JarInspector(context.files());
                List<String> problems = new ArrayList<>();

                for (McVersion version : context.plan().spigotVersions()) {
                    Path jar = context.layout().serverJar("Spigot", version.toString());
                    JarInspector.Result result =
                            inspector.inspectServerJar(jar, MINIMUM_JAR_BYTES);
                    if (!result.valid()) problems.add(version + ": " + result.problem());
                }
                if (!problems.isEmpty()) {
                    throw new VerificationFailedException(
                            "some Spigot jars are missing or unusable",
                            String.join("; ", problems),
                            "Re-run 'watchwolf build'. Only the versions that failed are retried.");
                }

                List<String> leftovers = context.docker()
                        .containersNamed(ContainerNames.SPIGOT_BUILDER_PREFIX).stream()
                        .map(container -> container.name()).toList();
                if (!leftovers.isEmpty()) {
                    throw new VerificationFailedException(
                            "Spigot build containers were left behind",
                            String.join(", ", leftovers),
                            "Remove them with 'docker rm " + String.join(" ", leftovers)
                                    + "'; their logs explain what happened first.");
                }
            }
        };
    }
}
