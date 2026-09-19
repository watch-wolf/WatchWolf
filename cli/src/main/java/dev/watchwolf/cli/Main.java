package dev.watchwolf.cli;

import dev.watchwolf.cli.command.WatchWolfCli;
import picocli.CommandLine;

/**
 * Entry point. Kept thin on purpose: everything testable lives in the command classes.
 */
public final class Main {
    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** Separated from {@link #main} so tests can assert exit codes without killing the JVM. */
    public static int run(String[] args) {
        CommandLine commandLine = new CommandLine(new WatchWolfCli())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setUsageHelpAutoWidth(true)
                .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
                    cmd.getErr().println("[e] " + describe(ex));
                    if (System.getenv("WW_VERBOSE") != null) {
                        ex.printStackTrace(cmd.getErr());
                    }
                    return ExitCodes.ERROR;
                });
        return commandLine.execute(args);
    }

    private static String describe(Throwable ex) {
        String message = ex.getMessage();
        if (message != null && !message.isBlank()) return message;
        return ex.getClass().getSimpleName();
    }
}
