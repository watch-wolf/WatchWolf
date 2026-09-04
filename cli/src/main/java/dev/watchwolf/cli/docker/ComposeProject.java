package dev.watchwolf.cli.docker;

import dev.watchwolf.cli.layout.InstallLayout;
import dev.watchwolf.cli.net.HostInterfaces;
import dev.watchwolf.cli.proc.CommandResult;
import dev.watchwolf.cli.proc.CommandRunner;
import dev.watchwolf.cli.progress.ProgressSink;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives {@code docker compose} for the ServersManager.
 *
 * <p>Compose is the one thing docker-java cannot do for us, so this shells out. Two hazards it
 * exists to contain:
 *
 * <ul>
 *   <li><b>The project name.</b> The built image is {@code <project>-servers-manager}, and the
 *       project defaults to the compose directory's name ({@code release}). An inherited
 *       {@code COMPOSE_PROJECT_NAME} silently renames it and breaks every image check, so
 *       {@code -p} is always passed explicitly and {@code COMPOSE_*} is scrubbed from the child.</li>
 *   <li><b>The environment.</b> {@code run.sh} computes MACHINE_IP/PUBLIC_IP/PARENT_PWD/
 *       SERVER_PATH_SHIFT inline; we compute them here instead, and give the {@code PUBLIC_IP}
 *       lookup a timeout -- {@code run.sh}'s bare {@code curl ifconfig.me} hangs the whole startup
 *       when the machine is offline.</li>
 * </ul>
 *
 * <p>The working directory is the runtime dir, which the launcher's identity mount makes the same
 * path inside and out -- so the absolute paths compose sends the daemon are host-valid.
 */
public final class ComposeProject {
    private final InstallLayout layout;
    private final CommandRunner commands;
    private final HostInterfaces interfaces;

    public ComposeProject(InstallLayout layout, CommandRunner commands, HostInterfaces interfaces) {
        this.layout = layout;
        this.commands = commands;
        this.interfaces = interfaces;
    }

    public String projectName() {
        return this.layout.flavor().composeProject();
    }

    public String imageName() {
        return this.layout.flavor().serversManagerImage();
    }

    /** {@code docker compose -p <project> build}. */
    public CommandResult build(boolean noCache, ProgressSink progress) {
        List<String> argv = new ArrayList<>(this.baseArgv());
        argv.add("build");
        if (noCache) argv.add("--no-cache");
        progress.begin("Building the ServersManager image (" + this.imageName() + ")");
        CommandResult result = this.commands.run(this.layout.serversManagerRuntime(),
                this.environment(progress), Duration.ofMinutes(30), progress, argv);
        progress.end(result.succeeded() ? "built" : "failed");
        return result;
    }

    /** {@code docker compose -p <project> up --no-build --detach}. */
    public CommandResult up(ProgressSink progress) {
        List<String> argv = new ArrayList<>(this.baseArgv());
        argv.addAll(List.of("up", "--no-build", "--detach"));
        progress.begin("Starting the ServersManager (compose project '" + this.projectName() + "')");
        CommandResult result = this.commands.run(this.layout.serversManagerRuntime(),
                this.environment(progress), Duration.ofMinutes(5), progress, argv);
        progress.end(result.succeeded() ? "started" : "failed");
        return result;
    }

    /** {@code docker compose -p <project> down}. */
    public CommandResult down(ProgressSink progress) {
        List<String> argv = new ArrayList<>(this.baseArgv());
        argv.add("down");
        progress.begin("Stopping the ServersManager");
        CommandResult result = this.commands.run(this.layout.serversManagerRuntime(),
                this.environment(progress), Duration.ofMinutes(5), progress, argv);
        progress.end(result.succeeded() ? "stopped" : "failed");
        return result;
    }

    /** Visible for testing: the argv prefix every invocation shares. */
    public List<String> baseArgv() {
        return List.of("docker", "compose", "-p", this.projectName());
    }

    /**
     * The four variables the ServersManager's compose file interpolates.
     *
     * @see <a href="WatchWolf-ServersManager/AGENTS.md">its runtime contract</a>
     */
    public Map<String, String> environment(ProgressSink progress) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("MACHINE_IP", this.interfaces.preferredMachineIp());
        environment.put("PUBLIC_IP", this.interfaces.publicIp(progress));
        // host path == container path, thanks to the launcher's identity mount
        environment.put("PARENT_PWD", this.layout.serversManagerRuntime().toString());
        environment.put("SERVER_PATH_SHIFT", ".");
        return environment;
    }
}
