package dev.watchwolf.cli.command;

import dev.watchwolf.cli.ExitCodes;
import dev.watchwolf.cli.bundle.BundleWriter;
import dev.watchwolf.cli.tui.TerminalCapability;
import dev.watchwolf.cli.tui.monitor.MonitorPoller;
import dev.watchwolf.cli.tui.monitor.MonitorScreen;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * The live dashboard.
 *
 * <p>An explicit subcommand: bare {@code watchwolf} prints help, so nothing full-screen ever opens
 * by surprise in a script. {@code watchwolf status} is the plain-text equivalent.
 */
@Command(name = "monitor",
        header = "Live dashboard of the managers, their servers and their bots.",
        description = {
                "Arrows select, Enter opens one entity and shows its log, Escape comes back.",
                "'e' exports every log as one tar.gz. For plain text, use 'watchwolf status'."
        })
public class MonitorCommand implements Callable<Integer> {

    @Mixin
    GlobalOptions options = new GlobalOptions();

    @Option(names = "--interval", description = "Refresh interval in milliseconds.")
    long intervalMillis = 2000;

    @Override
    public Integer call() throws Exception {
        if (!TerminalCapability.available()) {
            System.err.println("[e] 'monitor' needs a terminal: "
                    + TerminalCapability.whyUnavailable() + ".");
            System.err.println("[e] Use 'watchwolf status' for a plain-text view, or run this "
                    + "from an interactive shell.");
            return ExitCodes.USAGE;
        }

        try (CliContext cli = new CliContext(this.options)) {
            Duration interval = Duration.ofMillis(Math.max(250, this.intervalMillis));

            MonitorPoller poller = new MonitorPoller(cli.scanner(), interval);
            BundleWriter bundleWriter = new BundleWriter(cli.docker(), cli.files(), cli.layout(),
                    cli.interfaces(), cli.clock());

            try (MonitorScreen screen = new MonitorScreen(cli.layout(), cli.docker(), cli.files(),
                    poller, bundleWriter, cli.progress(), interval)) {
                screen.run();
            }
            return ExitCodes.OK;
        }
    }
}
