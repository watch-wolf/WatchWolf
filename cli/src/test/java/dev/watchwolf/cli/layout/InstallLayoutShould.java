package dev.watchwolf.cli.layout;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

public class InstallLayoutShould {
    private final InstallLayout layout =
            new InstallLayout(Paths.get("/home/someone/WatchWolf"), RuntimeFlavor.RELEASE);

    @Test
    public void placeTheClonesUnderTheBase() {
        assertEquals(Path.of("/home/someone/WatchWolf/ServersManager"),
                layout.serversManagerRepo());
        assertEquals(Path.of("/home/someone/WatchWolf/ClientsManager"),
                layout.clientsManagerRepo());
        assertEquals(Path.of("/home/someone/WatchWolf/WatchWolf-Tester"),
                layout.testerRepo());
    }

    @Test
    public void resolveTheRuntimeDirectoryFromTheFlavour() {
        assertEquals(Path.of("/home/someone/WatchWolf/ServersManager/ci/release"),
                layout.serversManagerRuntime());
        assertEquals(Path.of("/home/someone/WatchWolf/ServersManager/ci/debug"),
                new InstallLayout(Paths.get("/home/someone/WatchWolf"), RuntimeFlavor.DEBUG)
                        .serversManagerRuntime());
    }

    @Test
    public void resolveTheServersManagerRuntimeContract() {
        Path runtime = Path.of("/home/someone/WatchWolf/ServersManager/ci/release");
        assertEquals(runtime.resolve("server-types"), layout.serverTypes());
        assertEquals(runtime.resolve("server-types/Spigot/1.8.8.jar"),
                layout.serverJar("Spigot", "1.8.8"));
        assertEquals(runtime.resolve("usual-plugins"), layout.usualPlugins());
        assertEquals(runtime.resolve("tmp"), layout.tmp());
        assertEquals(runtime.resolve("logs"), layout.logs());
        assertEquals(runtime.resolve("docker-compose.yml"), layout.composeFile());
        assertEquals(runtime.resolve("ServersManager.jar"), layout.serversManagerJar());
    }

    @Test
    public void joinASessionIdToBothItsLogsAndItsScratchFolder() {
        // MC_Server-<id>, tmp/<id>/ and logs/<id>/ all share the one id
        assertEquals(layout.logs().resolve("1772387923303"), layout.logs("1772387923303"));
        assertEquals(layout.logs("1772387923303").resolve("info.txt"),
                layout.sessionInfoFile("1772387923303"));
        assertEquals(layout.logs("1772387923303").resolve("latest.log"),
                layout.sessionLogFile("1772387923303"));
        assertEquals(layout.tmp().resolve("1772387923303"), layout.tmp("1772387923303"));
    }

    @Test
    public void pointAtTheTesterScriptTheSelfDiagnosisRuns() {
        assertEquals(Path.of("/home/someone/WatchWolf/WatchWolf-Tester/ci/tests.sh"),
                layout.testerTestsScript());
    }

    @Test
    public void keepItsStateOutOfTheWay() {
        assertEquals(Path.of("/home/someone/WatchWolf/.watchwolf"), layout.stateDir());
        assertEquals(layout.stateDir().resolve("owned-by-cli"), layout.ownershipMarker());
        assertEquals(layout.stateDir().resolve("host-action.sh"), layout.hostActionScript());
    }

    @Test
    public void deriveTheComposeProjectAndImageFromTheFlavour() {
        // the image name comes from the compose project, which defaults to the directory name
        assertEquals("release", RuntimeFlavor.RELEASE.composeProject());
        assertEquals("release-servers-manager:latest", RuntimeFlavor.RELEASE.serversManagerImage());
        assertEquals("debug-servers-manager:latest", RuntimeFlavor.DEBUG.serversManagerImage());
    }

    @Test
    public void makeTheBaseAbsolute() {
        assertTrue(new InstallLayout(Paths.get("relative/path"), RuntimeFlavor.RELEASE)
                .base().isAbsolute());
    }
}
