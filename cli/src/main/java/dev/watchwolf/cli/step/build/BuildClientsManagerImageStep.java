package dev.watchwolf.cli.step.build;

import dev.watchwolf.cli.docker.RunSpec;
import dev.watchwolf.cli.step.*;

import java.util.Set;

/**
 * Builds the ClientsManager image.
 *
 * <p>The verification does more than check the image exists: it runs
 * {@code python3 -c "import javascript"} inside it. The Python-to-Node bridge is the part of that
 * image that actually breaks -- it is installed by three separate {@code npm}/{@code pip} steps in
 * the Dockerfile -- and an image that builds but cannot import it produces bots that never connect,
 * with no message saying why.
 */
public final class BuildClientsManagerImageStep implements Step {
    public static final StepId ID = StepId.of("build-clients-manager-image");
    public static final String IMAGE = "clients-manager:latest";
    private static final String BASE_IMAGE = "nikolaik/python-nodejs";

    @Override public StepId id()    { return ID; }
    @Override public String title() { return "Build the ClientsManager image"; }

    @Override
    public Set<StepId> requires() {
        return Set.of(CloneStepIds.CLIENTS_MANAGER);
    }

    @Override
    public boolean isApplicable(StepContext context) {
        return context.plan().buildClientsManagerImage();
    }

    @Override
    public void perform(StepContext context) throws StepFailedException {
        if (!context.docker().imageExists(BASE_IMAGE)) {
            context.docker().pullImage(BASE_IMAGE, context.progress());
        }
        context.docker().buildImage(context.layout().clientsManagerRepo().toString(),
                IMAGE, context.progress());
    }

    @Override
    public Verification verification() {
        return new Verification() {
            @Override
            public String describe() {
                return IMAGE + " exists and can import the Python-to-Node bridge";
            }

            @Override
            public void check(StepContext context) throws VerificationFailedException {
                if (!context.docker().imageExists(IMAGE)) {
                    throw new VerificationFailedException(
                            "the ClientsManager image was not built",
                            "no image named " + IMAGE,
                            "Run 'watchwolf build' and check the build output.");
                }

                int exitCode;
                try {
                    exitCode = context.docker().runToCompletion(
                            RunSpec.of(IMAGE)
                                    .withEntrypoint("python3")
                                    .withCommand("-c", "import javascript"),
                            line -> context.progress().detail(line));
                } catch (RuntimeException ex) {
                    throw new VerificationFailedException(
                            "the ClientsManager image could not be checked",
                            ex.getMessage(),
                            "Run 'docker run --rm " + IMAGE
                                    + " python3 -c \"import javascript\"' to see the error.");
                }

                if (exitCode != 0) {
                    throw new VerificationFailedException(
                            "the ClientsManager image is broken",
                            "'python3 -c \"import javascript\"' exited " + exitCode
                                    + " -- the Python-to-Node bridge is not installed",
                            "Rebuild it with 'watchwolf build'. Bots would otherwise start and "
                                    + "never connect, with nothing in the log to say why.");
                }
            }
        };
    }
}
