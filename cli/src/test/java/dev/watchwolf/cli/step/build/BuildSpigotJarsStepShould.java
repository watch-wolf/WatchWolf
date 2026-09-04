package dev.watchwolf.cli.step.build;

import dev.watchwolf.cli.docker.RunSpec;
import dev.watchwolf.cli.fake.FakeDockerFacade;
import dev.watchwolf.cli.fake.FakeHttpFetcher;
import dev.watchwolf.cli.fake.RecordingCommandRunner;
import dev.watchwolf.cli.io.NioFileGateway;
import dev.watchwolf.cli.layout.InstallLayout;
import dev.watchwolf.cli.layout.RuntimeFlavor;
import dev.watchwolf.cli.model.BuildPlan;
import dev.watchwolf.cli.model.McVersion;
import dev.watchwolf.cli.net.HostInterfaces;
import dev.watchwolf.cli.parse.ContainerNames;
import dev.watchwolf.cli.progress.RecordingProgressSink;
import dev.watchwolf.cli.step.CancelSignal;
import dev.watchwolf.cli.step.HostAction;
import dev.watchwolf.cli.step.StepContext;
import dev.watchwolf.cli.step.StepFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The long step, and the only one that has to behave itself when a user changes their mind an hour
 * in: it reports each jar separately, it stops when asked, and it never collides with a builder
 * that is already running -- which is the normal state of things after an abort, since those
 * containers deliberately outlive the CLI.
 *
 * <p>Polls with no wait, so what would take a quarter of an hour of sleeping takes milliseconds.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
public class BuildSpigotJarsStepShould {
    @TempDir Path base;

    private InstallLayout layout;
    private FakeDockerFacade docker;
    private RecordingProgressSink progress;

    @BeforeEach
    void setUp() {
        this.layout = new InstallLayout(this.base, RuntimeFlavor.RELEASE);
        this.docker = new FakeDockerFacade();
        this.progress = new RecordingProgressSink();
    }

    @Test
    public void giveEveryVersionItsOwnProgressRow() {
        this.docker.withDetachedRunsFinishingImmediately();
        StepContext context = this.context(this.plan("1.8.8", "1.20.4"), CancelSignal.never());

        // no jar ever appears in the staging directory, so both versions fail -- which is fine
        // here: what is being asserted is that each one was reported on its own, not as a total
        assertThrows(StepFailedException.class, () -> new BuildSpigotJarsStep(0).perform(context));

        assertEquals(List.of("spigot-1.8.8", "spigot-1.20.4"),
                this.progress.taskIdsOf("task-queued"),
                "every selected version must get a row before any of them starts");
        assertEquals(List.of("spigot-1.8.8", "spigot-1.20.4"),
                this.progress.taskIdsOf("task-started"));
        assertEquals(List.of("spigot-1.8.8", "spigot-1.20.4"),
                this.progress.taskIdsOf("task-failed"));
    }

    @Test
    public void adoptABuilderThatIsAlreadyRunning() {
        // exactly what an aborted or backgrounded run leaves behind; starting a second container
        // with the same name is refused by Docker, so the step has to attach to this one
        this.docker.withContainer(ContainerNames.spigotBuilderFor("1.8.8")).running().done();

        AtomicBoolean cancelled = new AtomicBoolean();
        StepContext context = this.context(this.plan("1.8.8"), () -> {
            // false on the first check (so the builder is adopted), true afterwards, so the poll
            // loop ends instead of waiting an hour for a container the fake never finishes
            boolean answer = cancelled.get();
            cancelled.set(true);
            return answer;
        });

        StepFailedException failure = assertThrows(StepFailedException.class,
                () -> new BuildSpigotJarsStep(0).perform(context));

        assertTrue(this.docker.startedSpecs().isEmpty(),
                "the running builder must be adopted, not started again");
        assertTrue(failure.getMessage().contains("aborted at your request"),
                "an abort must not be reported as a build failure: " + failure.getMessage());
    }

    @Test
    public void clearAwayADeadBuilderBeforeStartingANewOne() {
        // Docker holds the name until the container is removed, so a leftover from a failed run
        // would otherwise make every retry fail with "name already in use"
        this.docker.withContainer(ContainerNames.spigotBuilderFor("1.8.8")).exited().done();
        this.docker.withDetachedRunsFinishingImmediately();

        StepContext context = this.context(this.plan("1.8.8"), CancelSignal.never());
        assertThrows(StepFailedException.class, () -> new BuildSpigotJarsStep(0).perform(context));

        assertEquals(List.of(ContainerNames.spigotBuilderFor("1.8.8")),
                this.docker.startedSpecs().stream().map(RunSpec::name).toList());
    }

    @Test
    public void queueTheVersionsThatDoNotFitTheParallelismYet() {
        this.docker.withDetachedRunsFinishingImmediately();
        BuildPlan plan = BuildPlan.builder()
                .buildSpigot(true)
                .spigotVersions(List.of(McVersion.of("1.8.8"), McVersion.of("1.16.5"),
                        McVersion.of("1.20.4")))
                .parallelBuilders(1)
                .build();

        assertThrows(StepFailedException.class,
                () -> new BuildSpigotJarsStep(0).perform(this.context(plan, CancelSignal.never())));

        // all three are announced up front, even though only one builder ever runs at a time
        assertEquals(List.of("spigot-1.8.8", "spigot-1.16.5", "spigot-1.20.4"),
                this.progress.taskIdsOf("task-queued"));
        assertEquals(1, this.progress.events().stream()
                        .filter(event -> event.kind().equals("task-started:spigot-1.20.4")).count(),
                "the last version starts once, when its turn finally comes");
    }

    @Test
    public void doNothingWhenEveryVersionIsAlreadyBuilt() throws Exception {
        BuildPlan plan = this.plan("1.8.8");
        Path jar = this.layout.serverJar("Spigot", "1.8.8");
        java.nio.file.Files.createDirectories(jar.getParent());
        java.nio.file.Files.writeString(jar, "pretend this is a jar");

        new BuildSpigotJarsStep(0).perform(this.context(plan, CancelSignal.never()));

        assertTrue(this.docker.startedSpecs().isEmpty());
        assertTrue(this.progress.taskIdsOf("task-queued").isEmpty());
        assertTrue(this.progress.taskIdsOf("task-started").isEmpty());
    }

    private BuildPlan plan(String... versions) {
        return BuildPlan.builder()
                .buildSpigot(true)
                .spigotVersions(List.of(versions).stream().map(McVersion::of).toList())
                .parallelBuilders(4)
                .build();
    }

    private StepContext context(BuildPlan plan, CancelSignal cancel) {
        return new StepContext(this.layout, plan, this.docker, new RecordingCommandRunner(),
                new NioFileGateway(), new FakeHttpFetcher(), new HostInterfaces(),
                Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC),
                this.progress, new HostAction(), cancel);
    }
}
