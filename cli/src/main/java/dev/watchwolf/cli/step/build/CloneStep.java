package dev.watchwolf.cli.step.build;

import dev.watchwolf.cli.proc.CommandResult;
import dev.watchwolf.cli.proc.GitRepository;
import dev.watchwolf.cli.step.Step;
import dev.watchwolf.cli.step.StepContext;
import dev.watchwolf.cli.step.StepFailedException;
import dev.watchwolf.cli.step.StepId;
import dev.watchwolf.cli.step.Verification;
import dev.watchwolf.cli.step.VerificationFailedException;
import dev.watchwolf.cli.step.Verifications;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Clones (or updates) one repository, then proves it is really there.
 *
 * <p>This is the step the whole framework was built for: {@code git clone} can exit 0 having left
 * a partial tree, and the Bash installer would then carry on and fail much later with something
 * unrecognisable. The verification asks the three questions that matter -- is it a git checkout, is
 * it on the branch we asked for, and are the files we depend on actually present.
 */
public final class CloneStep implements Step {
    private final StepId id;
    private final String title;
    private final Repositories.Descriptor repository;
    private final Path directory;
    private final Predicate<StepContext> selected;
    /** An extra postcondition beyond "it is really cloned", or null. */
    private final Verification additional;

    public CloneStep(StepId id, String title, Repositories.Descriptor repository, Path directory,
                     Predicate<StepContext> selected) {
        this(id, title, repository, directory, selected, null);
    }

    public CloneStep(StepId id, String title, Repositories.Descriptor repository, Path directory,
                     Predicate<StepContext> selected, Verification additional) {
        this.id = id;
        this.title = title;
        this.repository = repository;
        this.directory = directory;
        this.selected = selected;
        this.additional = additional;
    }

    @Override public StepId id()   { return this.id; }
    @Override public String title() { return this.title; }

    @Override
    public boolean isApplicable(StepContext context) {
        return this.selected.test(context);
    }

    @Override
    public String skipReason(StepContext context) {
        return "not selected in the build plan";
    }

    @Override
    public void perform(StepContext context) throws StepFailedException {
        String branch = context.plan().branch();
        GitRepository git = new GitRepository(context.commands(), this.directory);

        CommandResult result;
        if (context.files().isDirectory(this.directory.resolve(".git"))) {
            // never delete and re-clone: build must be idempotent, and it must never remove a
            // directory it did not create
            result = git.update(branch, context.progress());
            if (!result.succeeded()) {
                throw new StepFailedException(
                        "updating " + this.repository.directoryName(),
                        result.failureText(),
                        "The existing checkout at " + this.directory + " could not be fast-forwarded "
                                + "onto '" + branch + "'. Commit or stash your local changes, or "
                                + "move that directory aside and run the command again.");
            }
            return;
        }

        if (context.files().exists(this.directory)
                && !context.files().list(this.directory).isEmpty()) {
            throw new StepFailedException(
                    "cloning " + this.repository.directoryName(),
                    this.directory + " already exists, is not empty, and is not a git checkout",
                    "Move it aside and run the command again. This command never deletes a "
                            + "directory it did not create.");
        }

        result = git.clone(this.repository.url(), branch, context.progress());
        if (!result.succeeded()) {
            throw new StepFailedException(
                    "cloning " + this.repository.directoryName(),
                    result.failureText(),
                    "Check this machine can reach " + GitRepository.hostOf(this.repository.url())
                            + ", and that the branch '" + branch + "' exists.");
        }
    }

    @Override
    public Verification verification() {
        Verification cloned = this.cloneVerification();
        return this.additional == null ? cloned : Verifications.all(cloned, this.additional);
    }

    private Verification cloneVerification() {
        return new Verification() {
            @Override
            public String describe() {
                return this.repoName() + " is a git checkout on the requested branch, holding "
                        + CloneStep.this.repository.expectedFiles().length + " expected file(s)";
            }

            private String repoName() {
                return CloneStep.this.repository.directoryName();
            }

            @Override
            public void check(StepContext context) throws VerificationFailedException {
                Path directory = CloneStep.this.directory;

                if (!context.files().isDirectory(directory)) {
                    throw new VerificationFailedException(
                            this.repoName() + " was not cloned",
                            directory + " does not exist",
                            "Run 'watchwolf build' and check the clone step's output.");
                }
                if (!context.files().isDirectory(directory.resolve(".git"))) {
                    throw new VerificationFailedException(
                            this.repoName() + " is not a git checkout",
                            directory + " exists but has no .git directory",
                            "Move that directory aside and run 'watchwolf build' again.");
                }

                GitRepository git = new GitRepository(context.commands(), directory);
                String wanted = context.plan().branch();
                String actual = git.currentBranch().orElse("(unknown)");
                if (!wanted.equals(actual)) {
                    throw new VerificationFailedException(
                            this.repoName() + " is on the wrong branch",
                            "expected '" + wanted + "' but found '" + actual + "'",
                            "Run 'watchwolf build --branch " + wanted + "', or check out '"
                                    + wanted + "' in " + directory + " yourself.");
                }

                // a clone that exits 0 but left a partial tree is exactly what this catches
                List<String> missing = new ArrayList<>();
                for (String expected : CloneStep.this.repository.expectedFiles()) {
                    if (!context.files().exists(directory.resolve(expected))) missing.add(expected);
                }
                if (!missing.isEmpty()) {
                    throw new VerificationFailedException(
                            this.repoName() + " is incomplete",
                            "the checkout is missing " + String.join(", ", missing),
                            "The clone did not finish. Move " + directory + " aside and run "
                                    + "'watchwolf build' again.");
                }

                if (git.isDirty()) {
                    context.progress().warn(this.repoName() + " has uncommitted local changes; "
                            + "leaving them alone.");
                }
            }
        };
    }
}
