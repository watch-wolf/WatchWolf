package dev.watchwolf.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/**
 * The {@code watchwolf} command.
 *
 * <p>Bare {@code watchwolf} prints help: the live dashboard is reached explicitly with
 * {@code watchwolf monitor}, so nothing full-screen ever opens by surprise.
 */
@Command(
        name = "watchwolf",
        mixinStandardHelpOptions = true,
        versionProvider = WatchWolfCli.Version.class,
        synopsisSubcommandLabel = "COMMAND",
        header = "Build, install, run, monitor and diagnose a WatchWolf environment.",
        descriptionHeading = "%n",
        commandListHeading = "%nCommands:%n",
        optionListHeading = "%nOptions:%n",
        footerHeading = "%n",
        footer = {
                "The host needs nothing but Docker: this command runs inside its own image.",
                "Full documentation: https://github.com/watch-wolf/WatchWolf"
        },
        subcommands = {
                BuildCommand.class,
                InstallCommand.class,
                UninstallCommand.class,
                RunCommand.class,
                StopCommand.class,
                StatusCommand.class,
                MonitorCommand.class,
                LogsCommand.class,
                DoctorCommand.class,
                UpdateCommand.class,
                InternalCopyCommand.class,
        }
)
public class WatchWolfCli implements Callable<Integer> {
    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        this.spec.commandLine().usage(this.spec.commandLine().getOut());
        return dev.watchwolf.cli.ExitCodes.OK;
    }

    static class Version implements picocli.CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            Package self = WatchWolfCli.class.getPackage();
            String version = self == null ? null : self.getImplementationVersion();
            return new String[] { "watchwolf-cli " + (version == null ? "(development build)" : version) };
        }
    }
}
