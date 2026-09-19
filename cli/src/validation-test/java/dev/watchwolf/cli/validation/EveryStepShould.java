package dev.watchwolf.cli.validation;

import dev.watchwolf.cli.fake.FakeDockerFacade;
import dev.watchwolf.cli.fake.RecordingCommandRunner;
import dev.watchwolf.cli.io.NioFileGateway;
import dev.watchwolf.cli.layout.InstallLayout;
import dev.watchwolf.cli.layout.RuntimeFlavor;
import dev.watchwolf.cli.model.BuildPlan;
import dev.watchwolf.cli.net.HostInterfaces;
import dev.watchwolf.cli.progress.ProgressSink;
import dev.watchwolf.cli.remote.HttpFetcher;
import dev.watchwolf.cli.step.HostAction;
import dev.watchwolf.cli.step.Step;
import dev.watchwolf.cli.step.StepContext;
import dev.watchwolf.cli.step.StepGraph;
import dev.watchwolf.cli.step.build.StepCatalog;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;

/**
 * Every {@link Step} declared in {@link StepCatalog} carries the two things the whole framework
 * exists for: a title the user will actually see, and a {@link dev.watchwolf.cli.step.Verification}
 * with something to say -- {@code Verification.nothingToVerify(...)} is an acceptable answer, a
 * {@code null} is not.
 *
 * <p>Id uniqueness and every {@code requires()} resolving are enforced per-graph by
 * {@link StepGraph#of} itself (it throws on a duplicate id or an unresolved dependency within one
 * graph), which this test re-runs explicitly so that failure is attributed to the validation suite
 * rather than surfacing as a generic exception elsewhere. It is deliberately per-graph, not global:
 * {@code PreflightDockerStep} legitimately appears in both the build graph and the install graph,
 * which are never executed together, so that is not a real collision.
 *
 * <p>The remaining half of the framework's promise -- that every failure a step can raise carries a
 * non-blank remedy -- is enforced structurally, not by convention: both
 * {@code StepFailedException} and {@code VerificationFailedException} refuse to construct with a
 * blank one. A step that cannot tell the user what to do about its failure does not compile a
 * working failure path.
 */
public class EveryStepShould {

    @TestFactory
    Stream<DynamicTest> declareATitleAndAVerification() {
        // one flat list for title/verification checks; each graph is also built independently
        // below purely to prove StepGraph.of's per-graph id/dependency validation still passes --
        // a step legitimately reused across both graphs (PreflightDockerStep, run once per
        // invocation of each) is not a duplicate in the sense that matters, since the two graphs
        // never execute together
        StepContext context = fakeContext();
        StepGraph.of(new java.util.ArrayList<>(StepCatalog.buildGraph(context).ordered()));
        StepGraph.of(new java.util.ArrayList<>(StepCatalog.installGraph(context).ordered()));

        List<Step> steps = allSteps();

        return steps.stream().map(step -> DynamicTest.dynamicTest(
                step.getClass().getSimpleName() + " (" + step.id() + ")",
                () -> {
                    if (step.title() == null || step.title().isBlank()) {
                        throw new AssertionError(step.getClass().getSimpleName()
                                + " has a blank title, which is what the menu and the progress "
                                + "log show the user.");
                    }
                    if (step.verification() == null) {
                        throw new AssertionError(step.getClass().getSimpleName()
                                + " returns a null Verification. Use "
                                + "Verification.nothingToVerify(\"why\") if there really is "
                                + "nothing to check.");
                    }
                    String description = step.verification().describe();
                    if (description == null || description.isBlank()) {
                        throw new AssertionError(step.getClass().getSimpleName()
                                + "'s verification has a blank describe().");
                    }
                }));
    }

    /** Both graphs, built against fakes so this runs with no Docker daemon and no filesystem. */
    private static List<Step> allSteps() {
        StepContext context = fakeContext();
        List<Step> steps = new java.util.ArrayList<>();
        steps.addAll(StepCatalog.buildGraph(context).ordered());
        steps.addAll(StepCatalog.installGraph(context).ordered());
        return steps;
    }

    private static StepContext fakeContext() {
        Path base = Paths.get(System.getProperty("java.io.tmpdir"), "watchwolf-validation-fake");
        InstallLayout layout = new InstallLayout(base, RuntimeFlavor.RELEASE);

        return new StepContext(layout, BuildPlan.defaults(), new FakeDockerFacade(),
                new RecordingCommandRunner(), new NioFileGateway(), new NoopHttpFetcher(),
                new HostInterfaces(), Clock.systemUTC(), ProgressSink.discarding(),
                new HostAction());
    }

    /** HttpFetcher has two abstract methods, so a lambda cannot stand in for it. */
    private static final class NoopHttpFetcher implements HttpFetcher {
        @Override
        public String getString(String url, ProgressSink progress) {
            return "";
        }

        @Override
        public void download(String url, Path destination, ProgressSink progress) {
            // never called: describe()/title() do not perform() anything
        }
    }
}
