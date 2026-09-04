package dev.watchwolf.cli.tui.install;

import dev.watchwolf.cli.step.StepFailedException;
import dev.watchwolf.cli.step.StepId;
import dev.watchwolf.cli.step.StepOutcome;
import dev.watchwolf.cli.step.StepResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The install screen's whole state, with no terminal involved: what it says while running, what it
 * says once it stops, and -- the part worth pinning down -- when it refuses to invent a percentage.
 */
public class InstallProgressModelShould {

    @Test
    public void trackStepsAsTheyStartAndFinish() {
        InstallProgressModel model = new InstallProgressModel();
        model.runStarting(3);
        model.stepStarting("clone-servers-manager", "Clone the ServersManager");

        assertEquals(1, model.steps().size());
        assertTrue(model.steps().get(0).running(), "a started step must show as running");
        assertEquals(0, model.stepsFinished());

        model.stepFinished("clone-servers-manager", "Clone the ServersManager", StepOutcome.OK);

        assertFalse(model.steps().get(0).running());
        assertEquals(StepOutcome.OK, model.steps().get(0).outcome());
        assertEquals(1, model.stepsFinished());
        assertEquals(1 / 3.0, model.overallFraction(), 0.0001);
    }

    @Test
    public void keepABlockedStepThatNeverStarted() {
        InstallProgressModel model = new InstallProgressModel();
        model.runStarting(2);
        // blocked steps are reported straight to stepFinished -- they never get a stepStarting
        model.stepFinished("build-spigot", "Build the Spigot jars", StepOutcome.BLOCKED);

        assertEquals(1, model.steps().size());
        assertEquals(StepOutcome.BLOCKED, model.steps().get(0).outcome());
        assertEquals(1, model.stepsFinished());
    }

    @Test
    public void reportAFailureBeforeTheRunIsOver() {
        InstallProgressModel model = new InstallProgressModel();
        model.runStarting(2);
        model.stepStarting("pull-jdk-images", "Pull the JDK images");
        model.stepFinished("pull-jdk-images", "Pull the JDK images", StepOutcome.FAILED);

        // the results list only arrives at the end of the run; the header must not stay green
        // for the hour in between
        assertTrue(model.anythingFailed());
    }

    @Test
    public void refuseToInventAFractionForWorkOfUnknownLength() {
        InstallProgressModel model = new InstallProgressModel();
        model.operationStarted("Building Spigot 1.20.4");
        assertEquals(-1, model.currentFraction(), 0.0001, "no total means no bar, not a guess");

        model.operationUpdated("142MB/319MB", 142, 319);
        assertEquals(142 / 319.0, model.currentFraction(), 0.0001);
    }

    @Test
    public void giveEachConcurrentJarItsOwnRow() {
        InstallProgressModel model = new InstallProgressModel();
        model.taskStarted("spigot-1.8.8", "Spigot 1.8.8", 1_000L);
        model.taskStarted("spigot-1.20.4", "Spigot 1.20.4", 1_000L);
        model.taskUpdated("spigot-1.8.8", "Spigot 1.8.8", "Applying patches", -1, -1);

        assertEquals(List.of("spigot-1.8.8", "spigot-1.20.4"),
                model.tasks().stream().map(InstallProgressModel.Task::id).toList());
        assertEquals("Applying patches", model.tasks().get(0).detail());
        assertEquals(-1, model.tasks().get(0).fraction(), 0.0001);

        model.taskFinished("spigot-1.8.8", "Spigot 1.8.8", "built", true);
        assertTrue(model.tasks().get(0).finished());
        assertTrue(model.tasks().get(0).succeeded());
        assertEquals(1_000L, model.tasks().get(0).startedAtMillis(),
                "finishing must not lose when the jar started");
    }

    @Test
    public void summariseTheThreeWaysARunCanEnd() {
        assertEquals("install successful", finished(InstallProgressModel.Ending.COMPLETED,
                List.of(ok("clone-servers-manager"))).summaryLine());
        assertEquals("install aborted", finished(InstallProgressModel.Ending.ABORTED,
                List.of(ok("clone-servers-manager"))).summaryLine());
        assertEquals("install still running in the background",
                finished(InstallProgressModel.Ending.BACKGROUNDED, List.of()).summaryLine());
        assertEquals("install failed: 1 step(s) of 2",
                finished(InstallProgressModel.Ending.COMPLETED,
                        List.of(ok("clone-servers-manager"), failed("build-spigot"))).summaryLine());
    }

    @Test
    public void onlyCountAsFinishedOnceItIsToldHowItEnded() {
        InstallProgressModel model = new InstallProgressModel();
        model.runStarting(1);
        model.stepStarting("clone-tester", "Clone the Tester");
        model.stepFinished("clone-tester", "Clone the Tester", StepOutcome.OK);

        // every step is done, but only the caller knows whether the user aborted, backgrounded,
        // or let it run out -- so the screen must not close on its own here
        assertFalse(model.isFinished());

        model.runFinished(List.of(ok("clone-tester")), InstallProgressModel.Ending.COMPLETED);
        assertTrue(model.isFinished());
    }

    @Test
    public void dropTheRunningJarsWhenTheRunEnds() {
        InstallProgressModel model = new InstallProgressModel();
        model.taskStarted("spigot-1.8.8", "Spigot 1.8.8", 1_000L);
        model.operationStarted("Building Spigot");

        model.runFinished(List.of(), InstallProgressModel.Ending.ABORTED);

        assertTrue(model.tasks().isEmpty(), "a finished run has nothing still building");
        assertTrue(model.currentOperation().isEmpty());
    }

    private static InstallProgressModel finished(InstallProgressModel.Ending ending,
                                                 List<StepResult> results) {
        InstallProgressModel model = new InstallProgressModel();
        model.runStarting(results.size());
        model.runFinished(results, ending);
        return model;
    }

    private static StepResult ok(String id) {
        return StepResult.ok(StepId.of(id), id, Duration.ofSeconds(1));
    }

    private static StepResult failed(String id) {
        return StepResult.failed(StepId.of(id), id, Duration.ofSeconds(1),
                new StepFailedException(id, "something broke", "try again"));
    }
}
