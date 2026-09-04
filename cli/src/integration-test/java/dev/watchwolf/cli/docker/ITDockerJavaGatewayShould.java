package dev.watchwolf.cli.docker;

import dev.watchwolf.cli.progress.ProgressSink;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the real {@link DockerJavaFacade} honours the contract {@link FakeDockerFacade} assumes
 * on the unit side -- the gap the fakes cannot close by construction. Needs a reachable Docker
 * daemon; {@code ci/tests.sh --integration} preflights that before this runs.
 *
 * <p>Everything here runs against one throwaway {@code alpine} container this class starts and
 * removes itself, so it never depends on -- or disturbs -- a real WatchWolf install.
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
public class ITDockerJavaGatewayShould {
    private static final String IMAGE = "alpine:latest";
    private static DockerJavaFacade docker;
    private static String containerName;

    @BeforeAll
    static void startContainer() {
        docker = DockerJavaFacade.connect();
        if (!docker.imageExists(IMAGE)) {
            docker.pullImage(IMAGE, ProgressSink.discarding());
        }

        containerName = "watchwolf-cli-it-" + UUID.randomUUID();
        docker.runDetached(RunSpec.of(IMAGE)
                .named(containerName)
                .withEntrypoint("sh", "-c")
                .withCommand("echo hello from the container; sleep 120")
                .autoRemove(true));

        // give the daemon a moment to report it as running before the tests below assert on it
        waitUntil(() -> docker.findContainer(containerName)
                .map(ContainerSnapshot::isRunning).orElse(false));
    }

    @AfterAll
    static void stopContainer() {
        if (docker == null) return;
        try {
            if (containerName != null) docker.stopContainer(containerName, 5);
        } finally {
            docker.close();
        }
    }

    @Test
    void listTheContainerItStarted() {
        List<ContainerSnapshot> containers = docker.listContainers();
        assertTrue(containers.stream().anyMatch(c -> c.name().equals(containerName)),
                "expected to find " + containerName + " among " + containers.size()
                        + " container(s)");
    }

    @Test
    void reportItAsRunning() {
        ContainerSnapshot snapshot = docker.findContainer(containerName).orElseThrow();
        assertTrue(snapshot.isRunning());
        assertEquals(IMAGE, snapshot.image());
    }

    @Test
    void normaliseTheLeadingSlashDockerNamesCarry() {
        // Docker reports names as "/name"; DockerizedServerInstantiator's own cleanup compares
        // ids against those raw names and so never matches anything -- our gateway must not
        // repeat that mistake
        Optional<ContainerSnapshot> found = docker.findContainer(containerName);
        assertTrue(found.isPresent());
        assertFalse(found.get().name().startsWith("/"),
                "ContainerSnapshot.name() must already be normalised");
    }

    @Test
    void readItsLogs() {
        waitUntil(() -> !docker.logs(containerName, 10).isEmpty());
        List<String> logs = docker.logs(containerName, 10);
        assertTrue(logs.stream().anyMatch(line -> line.contains("hello from the container")),
                "logs were: " + logs);
    }

    @Test
    void execACommandInsideIt() {
        String output = docker.exec(containerName, "echo", "exec-worked");
        assertTrue(output.contains("exec-worked"), "exec output was: " + output);
    }

    @Test
    void runAContainerToCompletionAndReportItsExitCode() {
        int exitCode = docker.runToCompletion(
                RunSpec.of(IMAGE).withEntrypoint("sh", "-c").withCommand("exit 7"), line -> { });
        assertEquals(7, exitCode);
    }

    @Test
    void reportTheDaemonAsReachable() {
        DaemonInfo info = docker.daemonInfo();
        assertTrue(info.reachable());
        assertNotNull(info.serverVersion());
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
