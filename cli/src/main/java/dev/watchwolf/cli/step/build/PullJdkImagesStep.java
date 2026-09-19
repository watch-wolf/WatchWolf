package dev.watchwolf.cli.step.build;

import dev.watchwolf.cli.inventory.ServerJarInventory;
import dev.watchwolf.cli.model.JavaImageCatalog;
import dev.watchwolf.cli.model.McVersion;
import dev.watchwolf.cli.step.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pre-pulls the JDK images Minecraft servers run on.
 *
 * <p><b>Fixes a real drift while porting.</b> {@code WatchWolfSetup.sh} pulls
 * {@code openjdk:{8,16,17}}, but {@code DockerizedServerInstantiator} launches
 * {@code eclipse-temurin:<v>-jdk} and {@code DockerUtilities.getJavaVersion} returns <b>21</b> for
 * MC 1.20.5+. So the script's pulls were the wrong names and missed one, and the right images were
 * fetched lazily -- or failed -- at server-start time. Both this list and {@code doctor}'s check
 * come from {@link JavaImageCatalog}, so there is one place to be wrong.
 */
public final class PullJdkImagesStep implements Step {
    public static final StepId ID = StepId.of("pull-jdk-images");

    @Override public StepId id()    { return ID; }
    @Override public String title() { return "Pull the JDK images servers run on"; }

    @Override
    public boolean isApplicable(StepContext context) {
        return context.plan().pullJdkImages();
    }

    @Override
    public String skipReason(StepContext context) {
        return "not selected; images will be pulled lazily at server start";
    }

    @Override
    public void perform(StepContext context) throws StepFailedException {
        for (String image : JavaImageCatalog.allRequiredImages()) {
            if (context.docker().imageExists(image)) {
                context.progress().detail(image + " is already present");
                continue;
            }
            context.docker().pullImage(image, context.progress());
        }
    }

    @Override
    public Verification verification() {
        return new Verification() {
            @Override
            public String describe() {
                return "every JDK image a server can need is present locally, and covers the "
                        + "versions in server-types/";
            }

            @Override
            public void check(StepContext context) throws VerificationFailedException {
                List<String> missing = new ArrayList<>();
                for (String image : JavaImageCatalog.allRequiredImages()) {
                    if (!context.docker().imageExists(image)) missing.add(image);
                }
                if (!missing.isEmpty()) {
                    throw new VerificationFailedException(
                            "JDK images are missing",
                            String.join(", ", missing) + " not present locally",
                            "Run 'watchwolf build'. Without them, starting a server of the "
                                    + "matching Minecraft version pulls at run time, or fails.");
                }

                // coverage: the versions actually installed must all have their image
                Set<McVersion> installed = new ServerJarInventory(context.files(),
                        context.layout()).installedVersions();
                Set<String> needed = JavaImageCatalog.imagesRequiredBy(installed);
                List<String> uncovered = new ArrayList<>();
                for (String image : needed) {
                    if (!context.docker().imageExists(image)) uncovered.add(image);
                }
                if (!uncovered.isEmpty()) {
                    throw new VerificationFailedException(
                            "an installed server version has no JDK image",
                            String.join(", ", uncovered) + " is needed by a jar in server-types/",
                            "Run 'watchwolf build' with 'Pull JDK images' selected.");
                }
            }
        };
    }

}
