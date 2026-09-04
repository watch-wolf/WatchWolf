package dev.watchwolf.cli.step.build;

import dev.watchwolf.cli.model.TesterSuiteCatalog;
import dev.watchwolf.cli.step.StepContext;
import dev.watchwolf.cli.step.Verification;
import dev.watchwolf.cli.step.VerificationFailedException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Asserts every suite named in {@link TesterSuiteCatalog} exists in the Tester checkout.
 *
 * <p>The catalog has to be hardcoded -- the menu offers it before the Tester is even cloned -- so
 * this is its safety net. An upstream rename fails here, at build time, naming the suite, instead
 * of three minutes into a {@code doctor} run with an empty test report and no explanation.
 */
public final class TesterSuitesResolveVerification implements Verification {

    @Override
    public String describe() {
        return "every self-test suite in the catalog resolves to a file under "
                + "src/integration-test/java";
    }

    @Override
    public void check(StepContext context) throws VerificationFailedException {
        Path root = context.layout().testerIntegrationTests();
        if (!context.files().isDirectory(root)) {
            throw new VerificationFailedException(
                    "the Tester checkout has no integration tests",
                    root + " does not exist",
                    "The clone is incomplete. Move " + context.layout().testerRepo()
                            + " aside and run 'watchwolf build' again.");
        }

        Set<String> found = new LinkedHashSet<>();
        collectClassNames(context, root, found);

        List<String> missing = new ArrayList<>();
        for (String suite : TesterSuiteCatalog.allClassNames()) {
            if (!found.contains(suite)) missing.add(suite);
        }

        if (!missing.isEmpty()) {
            throw new VerificationFailedException(
                    "the self-test catalog names suites this Tester does not have",
                    String.join(", ", missing) + " not found under " + root,
                    "WatchWolf-Tester has renamed or removed them. Update TesterSuiteCatalog in "
                            + "the CLI, or pin the Tester to a branch that still has them.");
        }
    }

    private static void collectClassNames(StepContext context, Path directory, Set<String> into) {
        for (Path entry : context.files().list(directory)) {
            if (context.files().isDirectory(entry)) {
                collectClassNames(context, entry, into);
                continue;
            }
            String name = entry.getFileName().toString();
            if (name.endsWith(".java")) into.add(name.substring(0, name.length() - 5));
        }
    }
}
