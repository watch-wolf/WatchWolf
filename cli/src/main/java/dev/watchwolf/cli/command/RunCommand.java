package dev.watchwolf.cli.command;

import dev.watchwolf.cli.ExitCodes;
import dev.watchwolf.cli.docker.ComposeProject;
import dev.watchwolf.cli.docker.RunSpec;
import dev.watchwolf.cli.doctor.CheckResult;
import dev.watchwolf.cli.doctor.CompatibilityMatrixSource;
import dev.watchwolf.cli.doctor.DoctorReport;
import dev.watchwolf.cli.doctor.Tier1Suite;
import dev.watchwolf.cli.inventory.ManagerStatus;
import dev.watchwolf.cli.model.BuildPlan;
import dev.watchwolf.cli.parse.ContainerNames;
import dev.watchwolf.cli.proc.CommandResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Starts the two managers.
 *
 * <p>Runs the fast static checks first, because every one of them is a reason the managers would
 * come up and then fail confusingly -- a port already taken, no server jars, no WatchWolf-Server
 * plugin.
 */
@Command(name = "run",
        header = "Start the ServersManager and the ClientsManager.",
        description = "Runs the fast preflight checks first; pass --skip-checks to bypass them.")
public class RunCommand implements Callable<Integer> {

    @Mixin
    GlobalOptions options = new GlobalOptions();

    @Option(names = "--skip-checks", description = "Do not run the preflight checks.")
    boolean skipChecks;

    @Option(names = "--wait", description = "Wait until both managers accept connections.",
            defaultValue = "true", negatable = true)
    boolean wait;

    @Override
    public Integer call() {
        try (CliContext cli = new CliContext(this.options, "run")) {
            if (!this.skipChecks) {
                DoctorReport report = new Tier1Suite(
                        new CompatibilityMatrixSource.AbsentMatrixSource(),
                        cli.portProbe(), cli.interfaces())
                        .run(cli.stepContext(BuildPlan.defaults()), false);
                report.printTo(System.out);
                if (report.count(CheckResult.Severity.FAIL) > 0) {
                    System.err.println("[e] Not starting: the checks above would make this fail "
                            + "in a way that is hard to read later. Pass --skip-checks to insist.");
                    return ExitCodes.DOCTOR_FAILED;
                }
            }

            ComposeProject compose =
                    new ComposeProject(cli.layout(), cli.commands(), cli.interfaces());
            CommandResult serversManager = compose.up(cli.progress());
            if (!serversManager.succeeded()) {
                System.err.println("[e] Could not start the ServersManager: "
                        + serversManager.failureText());
                return ExitCodes.ERROR;
            }

            this.startClientsManager(cli);

            if (this.wait) return this.waitForBoth(cli);
            return ExitCodes.OK;
        }
    }

    private void startClientsManager(CliContext cli) {
        if (cli.docker().findContainer(ContainerNames.CLIENTS_MANAGER)
                .map(container -> container.isRunning()).orElse(false)) {
            cli.progress().detail("the ClientsManager is already running");
            return;
        }
        // a stopped-but-present container would block the name
        cli.docker().removeContainer(ContainerNames.CLIENTS_MANAGER, true);

        cli.progress().begin("Starting the ClientsManager (ports 7000-7199)");
        cli.docker().runDetached(RunSpec.of("clients-manager:latest")
                .named(ContainerNames.CLIENTS_MANAGER)
                .env("MACHINE_IP", cli.interfaces().preferredMachineIp())
                .env("PUBLIC_IP", cli.interfaces().publicIp(cli.progress()))
                .autoRemove(true));
        cli.progress().end("started");
    }

    private int waitForBoth(CliContext cli) {
        cli.progress().begin("Waiting for both managers to accept connections");
        long deadline = System.currentTimeMillis() + 120_000;

        while (System.currentTimeMillis() < deadline) {
            boolean serversManager = cli.portProbe()
                    .isAccepting("127.0.0.1", ManagerStatus.Kind.SERVERS_MANAGER.port());
            boolean clientsManager = cli.portProbe()
                    .isAccepting("127.0.0.1", ManagerStatus.Kind.CLIENTS_MANAGER.port());

            if (serversManager && clientsManager) {
                cli.progress().end("both accepting");
                System.out.println("[i] WatchWolf is running. Point your tests at "
                        + cli.interfaces().preferredMachineIp() + ".");
                return ExitCodes.OK;
            }
            cli.progress().update("ServersManager " + (serversManager ? "up" : "waiting")
                    + ", ClientsManager " + (clientsManager ? "up" : "waiting"), -1, -1);
            sleep(2000);
        }

        cli.progress().end("timed out");
        System.err.println("[e] The containers started but one of them never accepted a "
                + "connection within 120s.");
        System.err.println("[e] Look at its output: watchwolf monitor, or "
                + "'docker logs ServersManager'.");
        return ExitCodes.NOT_RUNNING;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
