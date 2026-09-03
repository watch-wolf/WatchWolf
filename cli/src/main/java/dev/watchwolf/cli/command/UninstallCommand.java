package dev.watchwolf.cli.command;

import dev.watchwolf.cli.ExitCodes;
import dev.watchwolf.cli.step.HostAction;
import dev.watchwolf.cli.step.install.RegisterLauncherStep;
import dev.watchwolf.cli.step.install.RegisterStartupStep;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

/**
 * Undoes {@code install}.
 *
 * <p>Removes only what {@code install} created -- the launcher and the boot registration. It does
 * <b>not</b> touch the install directory: that holds hours of Spigot builds and the server logs
 * somebody may still need. {@code --purge} says so explicitly and still refuses a directory the
 * CLI did not create.
 */
@Command(name = "uninstall",
        header = "Undo 'watchwolf install'.",
        description = "Removes the launcher and the boot service. The install directory is kept "
                + "unless --purge.")
public class UninstallCommand implements Callable<Integer> {

    @Mixin
    GlobalOptions options = new GlobalOptions();

    @Option(names = "--purge",
            description = "Also delete the install directory (server jars, logs and all).")
    boolean purge;

    @Override
    public Integer call() {
        try (CliContext cli = new CliContext(this.options)) {
            HostAction action = cli.hostAction();

            if (cli.files().exists(Paths.get(RegisterLauncherStep.TARGET))) {
                action.add("Remove the WatchWolf launcher.",
                        "rm -f " + RegisterLauncherStep.TARGET).requiringRoot();
            }

            if (cli.files().exists(Paths.get(RegisterStartupStep.UNIT_PATH))) {
                action.add("Remove the WatchWolf boot service.",
                        "systemctl disable watchwolf || true",
                        "rm -f " + RegisterStartupStep.UNIT_PATH,
                        "systemctl daemon-reload").requiringRoot();
            }

            if (this.purge) {
                Path base = cli.layout().base();
                if (!cli.files().exists(cli.layout().ownershipMarker())) {
                    System.err.println("[e] Refusing to purge " + base
                            + ": it carries no marker saying this command created it.");
                    System.err.println("[e] Delete it yourself if you are sure.");
                    return ExitCodes.ERROR;
                }
                // logs/ and tmp/ are root-owned (written by the ServersManager container), so the
                // removal has to be a host action even though the rest is ours
                action.add("Delete the WatchWolf install directory, including every server jar "
                                + "and log.",
                        "rm -rf " + HostAction.quote(base.toString())).requiringRoot();
            }

            if (action.isEmpty()) {
                System.out.println("[i] Nothing to remove: WatchWolf was not installed on this "
                        + "machine (the install directory, if any, is untouched).");
                return ExitCodes.OK;
            }

            return HostActionFlush.flush(cli, ExitCodes.OK);
        }
    }
}
