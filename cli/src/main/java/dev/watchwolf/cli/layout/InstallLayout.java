package dev.watchwolf.cli.layout;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Every path in a WatchWolf install, derived from one base directory.
 *
 * <p><b>This class performs no I/O.</b> It is pure {@link Path} algebra so it can be unit-tested
 * against a temp directory (or Jimfs) with no filesystem at all. {@link InstallLayoutFactory} does
 * the one existence check needed to pick a {@link RuntimeFlavor}.
 *
 * <p>The layout mirrors what {@code WatchWolfSetup.sh} creates and what the ServersManager expects
 * at runtime:
 *
 * <pre>
 * &lt;base&gt;/
 * ├── .watchwolf/                  CLI state (install marker, host-action script, caches)
 * ├── ServersManager/              clone of WatchWolf-ServersManager
 * │   ├── src/tools/               SpigotBuilder.sh, PaperBuilder.sh (legacy, kept for the shim)
 * │   └── ci/release/              THE RUNTIME DIRECTORY
 * │       ├── docker-compose.yml   container ServersManager, 8000:8000
 * │       ├── ServersManager.jar
 * │       ├── server-types/&lt;Type&gt;/&lt;version&gt;.jar
 * │       ├── usual-plugins/&lt;Name&gt;-&lt;ver&gt;-&lt;minMc&gt;-&lt;maxMc&gt;.jar
 * │       ├── tmp/&lt;millis&gt;/        per-server scratch, deleted when the server stops
 * │       └── logs/&lt;millis&gt;/       info.txt + latest.log, KEPT after the server dies
 * ├── ClientsManager/              clone of WatchWolf-Client
 * └── WatchWolf-Tester/            clone of WatchWolf-Tester (optional; powers the self-test)
 * </pre>
 */
public final class InstallLayout {
    /** Marker written by the CLI so {@code build} never deletes a directory it did not create. */
    public static final String OWNERSHIP_MARKER = "owned-by-cli";

    private final Path base;
    private final RuntimeFlavor flavor;

    public InstallLayout(Path base, RuntimeFlavor flavor) {
        this.base = Objects.requireNonNull(base, "base").toAbsolutePath();
        this.flavor = Objects.requireNonNull(flavor, "flavor");
    }

    public Path base() {
        return this.base;
    }

    public RuntimeFlavor flavor() {
        return this.flavor;
    }

    // ---- CLI state -------------------------------------------------------------------------

    public Path stateDir() {
        return this.base.resolve(".watchwolf");
    }

    public Path ownershipMarker() {
        return this.stateDir().resolve(OWNERSHIP_MARKER);
    }

    public Path hostActionScript() {
        return this.stateDir().resolve("host-action.sh");
    }

    public Path hostActionNeedsRootMarker() {
        return this.stateDir().resolve("host-action.needs-root");
    }

    public Path buildPlanFile() {
        return this.stateDir().resolve("install.yaml");
    }

    // ---- the three clones ------------------------------------------------------------------

    public Path serversManagerRepo() {
        return this.base.resolve("ServersManager");
    }

    public Path clientsManagerRepo() {
        return this.base.resolve("ClientsManager");
    }

    public Path testerRepo() {
        return this.base.resolve("WatchWolf-Tester");
    }

    /** The legacy Spigot/Paper builder scripts. Ported to Java; kept so the shim keeps working. */
    public Path serverBuilderTools() {
        return this.serversManagerRepo().resolve("src").resolve("tools");
    }

    /** {@code ci/tests.sh} in the Tester clone -- what {@code doctor}'s tier 2 invokes. */
    public Path testerTestsScript() {
        return this.testerRepo().resolve("ci").resolve("tests.sh");
    }

    public Path testerIntegrationTests() {
        return this.testerRepo().resolve("src").resolve("integration-test").resolve("java");
    }

    // ---- the ServersManager runtime directory ----------------------------------------------

    /** {@code <base>/ServersManager/ci/<flavour>} -- the compose project directory. */
    public Path serversManagerRuntime() {
        return this.serversManagerRepo().resolve("ci").resolve(this.flavor.directoryName());
    }

    public Path composeFile() {
        return this.serversManagerRuntime().resolve("docker-compose.yml");
    }

    public Path serversManagerJar() {
        return this.serversManagerRuntime().resolve("ServersManager.jar");
    }

    public Path serverTypes() {
        return this.serversManagerRuntime().resolve("server-types");
    }

    public Path serverTypes(String type) {
        return this.serverTypes().resolve(type);
    }

    /** e.g. {@code server-types/Spigot/1.8.8.jar}. */
    public Path serverJar(String type, String version) {
        return this.serverTypes(type).resolve(version + ".jar");
    }

    public Path usualPlugins() {
        return this.serversManagerRuntime().resolve("usual-plugins");
    }

    public Path logs() {
        return this.serversManagerRuntime().resolve("logs");
    }

    /** {@code logs/<millis>} -- the same id as the {@code MC_Server-<millis>} container. */
    public Path logs(String sessionId) {
        return this.logs().resolve(sessionId);
    }

    public Path sessionInfoFile(String sessionId) {
        return this.logs(sessionId).resolve("info.txt");
    }

    public Path sessionLogFile(String sessionId) {
        return this.logs(sessionId).resolve("latest.log");
    }

    public Path tmp() {
        return this.serversManagerRuntime().resolve("tmp");
    }

    /** {@code tmp/<millis>} -- deleted when the server stops, so often absent. */
    public Path tmp(String sessionId) {
        return this.tmp().resolve(sessionId);
    }

    @Override
    public String toString() {
        return "InstallLayout[" + this.base + " (" + this.flavor.directoryName() + ")]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InstallLayout)) return false;
        InstallLayout other = (InstallLayout) o;
        return this.base.equals(other.base) && this.flavor == other.flavor;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.base, this.flavor);
    }
}
