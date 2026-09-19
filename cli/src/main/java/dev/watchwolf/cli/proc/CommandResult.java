package dev.watchwolf.cli.proc;

import java.util.List;

/** What a subprocess produced. */
public record CommandResult(int exitCode, List<String> stdout, List<String> stderr) {

    public boolean succeeded() {
        return this.exitCode == 0;
    }

    public String stdoutText() {
        return String.join("\n", this.stdout);
    }

    public String stderrText() {
        return String.join("\n", this.stderr);
    }

    /** stderr when there is any, else stdout -- whichever is likelier to explain a failure. */
    public String failureText() {
        String errors = this.stderrText().strip();
        return errors.isEmpty() ? this.stdoutText().strip() : errors;
    }

    public static CommandResult of(int exitCode, List<String> stdout, List<String> stderr) {
        return new CommandResult(exitCode, List.copyOf(stdout), List.copyOf(stderr));
    }
}
