package dev.watchwolf.cli.step.build;

import dev.watchwolf.cli.io.JarInspector;
import dev.watchwolf.cli.model.McVersion;
import dev.watchwolf.cli.remote.PaperApiClient;
import dev.watchwolf.cli.step.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Downloads the newest Paper build for each selected version. */
public final class DownloadPaperJarsStep implements Step {
    public static final StepId ID = StepId.of("download-paper");

    private static final long MINIMUM_JAR_BYTES = 5L * 1024 * 1024;

    @Override public StepId id()    { return ID; }
    @Override public String title() { return "Download the Paper server jars"; }

    @Override
    public Set<StepId> requires() {
        return Set.of(CreateRuntimeDirsStep.ID);
    }

    @Override
    public boolean isApplicable(StepContext context) {
        return context.plan().buildPaper() && !context.plan().paperVersions().isEmpty();
    }

    @Override
    public String skipReason(StepContext context) {
        return context.plan().buildPaper() ? "no Paper versions selected" : "Paper was skipped";
    }

    @Override
    public void perform(StepContext context) throws StepFailedException {
        PaperApiClient paper = new PaperApiClient(context.http());
        List<String> failures = new ArrayList<>();

        for (McVersion version : context.plan().paperVersions()) {
            Path destination = context.layout().serverJar("Paper", version.toString());
            if (context.files().exists(destination)) {
                context.progress().detail("Paper " + version + " is already downloaded");
                continue;
            }

            try {
                Optional<PaperApiClient.Download> download =
                        paper.latestBuild(version, context.progress());
                if (download.isEmpty()) {
                    failures.add(version + ": the API listed no builds for it");
                    continue;
                }
                // JdkHttpFetcher stages to .part and renames, so an interrupted download never
                // leaves a truncated jar behind
                context.http().download(download.get().url(), destination, context.progress());
            } catch (RuntimeException ex) {
                failures.add(version + ": " + ex.getMessage());
            }
        }

        if (!failures.isEmpty()) {
            throw new StepFailedException("downloading Paper",
                    String.join("; ", failures),
                    "Check this machine can reach api.papermc.io, then run 'watchwolf build' again.");
        }
    }

    @Override
    public Verification verification() {
        return new Verification() {
            @Override
            public String describe() {
                return "every selected Paper version is a valid jar in server-types/Paper/";
            }

            @Override
            public void check(StepContext context) throws VerificationFailedException {
                JarInspector inspector = new JarInspector(context.files());
                List<String> problems = new ArrayList<>();
                for (McVersion version : context.plan().paperVersions()) {
                    JarInspector.Result result = inspector.inspectServerJar(
                            context.layout().serverJar("Paper", version.toString()),
                            MINIMUM_JAR_BYTES);
                    if (!result.valid()) problems.add(version + ": " + result.problem());
                }
                if (!problems.isEmpty()) {
                    throw new VerificationFailedException(
                            "some Paper jars are missing or unusable",
                            String.join("; ", problems),
                            "Run 'watchwolf build' again to re-download them.");
                }
            }
        };
    }
}
