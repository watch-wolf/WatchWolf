package dev.watchwolf.cli.command;

import dev.watchwolf.cli.ExitCodes;
import dev.watchwolf.cli.docker.ComposeProject;
import dev.watchwolf.cli.docker.ContainerSnapshot;
import dev.watchwolf.cli.parse.ContainerNames;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Stops the managers, and by default the Minecraft servers they left behind.
 *
 * <p>The orphan sweep matters: the ServersManager's own cleanup compares its ids against Docker's
 * names, which come back with a leading slash, so it never matches and stopped runs can leave
 * containers holding ports 8001+. A second test run then fails immediately, and killing the
 * container by hand "fixes" it -- which is exactly the symptom in the bug report this work came
 * from.
 */
@Command(name = "stop",
        header = "Stop the WatchWolf containers.",
        description = "Also stops leftover MC_Server-* containers, unless --keep-servers.")
public class StopCommand implements Callable<Integer> {

    @Mixin
    GlobalOptions options = new GlobalOptions();

    @Option(names = "--keep-servers",
            description = "Leave running Minecraft servers alone.")
    boolean keepServers;

    @Override
    public Integer call() {
        try (CliContext cli = new CliContext(this.options, "stop")) {
            new ComposeProject(cli.layout(), cli.commands(), cli.interfaces()).down(cli.progress());

            if (cli.docker().findContainer(ContainerNames.CLIENTS_MANAGER).isPresent()) {
                cli.progress().begin("Stopping the ClientsManager");
                cli.docker().stopContainer(ContainerNames.CLIENTS_MANAGER, 10);
                cli.progress().end("stopped");
            }

            if (!this.keepServers) {
                List<ContainerSnapshot> servers =
                        cli.docker().containersNamed(ContainerNames.MC_SERVER_PREFIX).stream()
                                .filter(ContainerSnapshot::isRunning).toList();
                if (!servers.isEmpty()) {
                    cli.progress().begin("Stopping " + servers.size()
                            + " leftover Minecraft server container(s)");
                    for (ContainerSnapshot server : servers) {
                        cli.docker().stopContainer(server.name(), 20);
                        cli.progress().detail("stopped " + server.name());
                    }
                    cli.progress().end("stopped");
                }
            }

            System.out.println("[i] WatchWolf stopped.");
            return ExitCodes.OK;
        }
    }
}
