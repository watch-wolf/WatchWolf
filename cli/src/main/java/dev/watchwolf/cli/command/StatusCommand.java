package dev.watchwolf.cli.command;

import dev.watchwolf.cli.ExitCodes;
import dev.watchwolf.cli.inventory.ClientStatus;
import dev.watchwolf.cli.inventory.EnvironmentSnapshot;
import dev.watchwolf.cli.inventory.ManagerStatus;
import dev.watchwolf.cli.inventory.McServerStatus;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * A one-shot, scriptable picture of what is running.
 *
 * <p>Deliberately plain text, never full-screen: {@code watchwolf monitor} is the live dashboard.
 * Keeping them separate means {@code status} stays safe in a pipe, a cron job and a CI log.
 */
@Command(name = "status",
        header = "Show what is running right now.",
        description = "Plain text, safe to pipe. For the live dashboard, use 'watchwolf monitor'.")
public class StatusCommand implements Callable<Integer> {

    @Mixin
    GlobalOptions options = new GlobalOptions();

    @Override
    public Integer call() {
        try (CliContext cli = new CliContext(this.options, "status")) {
            EnvironmentSnapshot snapshot = cli.scanner().scan();

            if (!snapshot.dockerReachable()) {
                System.err.println("[e] Docker is not reachable: "
                        + snapshot.dockerUnreachableReason().orElse("unknown reason"));
                System.err.println("[e] Start Docker, then try again.");
                return ExitCodes.ERROR;
            }

            System.out.println("install   " + cli.layout().base()
                    + "  (" + cli.layout().flavor().directoryName() + ")");
            System.out.println("docker    " + snapshot.dockerVersion());
            System.out.println("advertise " + snapshot.advertisedAddress()
                    + (snapshot.hostNetworkingTruthful() ? "" : "   (container view -- Docker Desktop)"));
            System.out.println();

            System.out.printf("%-18s %-9s %-12s %-8s %s%n",
                    "MANAGER", "STATE", "PORTS", "UPTIME", "IMAGE");
            for (ManagerStatus manager : snapshot.managers()) {
                System.out.printf("%-18s %-9s %-12s %-8s %s%n",
                        manager.name(), manager.stateLabel(), manager.kind().portLabel(),
                        manager.uptime(snapshot.takenAt()).map(StatusCommand::human).orElse("-"),
                        manager.image().orElse("-"));
            }

            System.out.println();
            System.out.printf("%-26s %-8s %-8s %-12s %-9s %s%n",
                    "SERVER", "TYPE", "VERSION", "PORTS", "STATE", "SESSION");
            if (snapshot.servers().isEmpty()) {
                System.out.println("(none)");
            } else {
                for (McServerStatus server : snapshot.servers()) {
                    System.out.printf("%-26s %-8s %-8s %-12s %-9s %s%n",
                            server.name(), server.type(), server.version(), server.portsLabel(),
                            server.isRunning() ? "running" : "finished", server.sessionId());
                }
            }

            System.out.println();
            System.out.println(snapshot.clients().sourceLabel().toUpperCase());
            if (snapshot.clients().clients().isEmpty()) {
                System.out.println("(none)");
            } else {
                System.out.printf("%-22s %-12s %-10s %s%n",
                        "CLIENT", "PORTS", "SOURCE", "JOINED SERVER");
                for (ClientStatus client : snapshot.clients().clients()) {
                    System.out.printf("%-22s %-12s %-10s %s%n",
                            client.displayName(), client.portsLabel(),
                            client.confidence().name().toLowerCase(),
                            client.minecraftServer().orElse("-"));
                }
            }
            // the limitation is part of the answer, not a footnote: it says which rows were guessed
            if (snapshot.clients().limitation() != null) {
                System.out.println("note: " + snapshot.clients().limitation());
            }

            return snapshot.anythingRunning() ? ExitCodes.OK : ExitCodes.NOT_RUNNING;
        }
    }

    static String human(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m";
        if (seconds < 86400) return (seconds / 3600) + "h" + ((seconds % 3600) / 60) + "m";
        return (seconds / 86400) + "d";
    }
}
