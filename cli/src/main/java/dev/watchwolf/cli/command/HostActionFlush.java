package dev.watchwolf.cli.command;

import dev.watchwolf.cli.ExitCodes;
import dev.watchwolf.cli.step.HostAction;

import java.io.IOException;

/**
 * Writes the pending host-action script and reports whether the launcher must run it.
 *
 * <p>The container cannot write {@code /etc} or drive systemd, so a step that needs one of those
 * queues the commands instead. This writes them out and returns
 * {@link ExitCodes#HOST_ACTION_REQUIRED}; the launcher then prints the script <em>in full</em>,
 * asks, and runs it. The user sees every root command before it happens.
 */
final class HostActionFlush {

    private HostActionFlush() {
    }

    /** @return the exit code to return from the command */
    static int flush(CliContext cli, int successCode) {
        HostAction action = cli.hostAction();
        if (action.isEmpty()) return successCode;

        try {
            cli.files().createDirectories(cli.layout().stateDir());
            cli.files().writeString(cli.layout().hostActionScript(), action.script());
            if (action.needsRoot()) {
                cli.files().writeString(cli.layout().hostActionNeedsRootMarker(), "");
            } else {
                cli.files().delete(cli.layout().hostActionNeedsRootMarker());
            }
        } catch (IOException ex) {
            System.err.println("[e] Could not write the host-action script to "
                    + cli.layout().hostActionScript() + ": " + ex.getMessage());
            System.err.println("[e] These commands still need to run on the host:");
            System.err.println(action.script());
            return ExitCodes.ERROR;
        }

        return ExitCodes.HOST_ACTION_REQUIRED;
    }
}
