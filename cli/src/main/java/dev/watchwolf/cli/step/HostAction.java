package dev.watchwolf.cli.step;

import java.util.ArrayList;
import java.util.List;

/**
 * Commands that only the host can run.
 *
 * <p>The CLI runs in an unprivileged container: it cannot write {@code /etc}, drive systemd, or
 * create a symlink in {@code /usr/local/bin}. Rather than pretend, a step that needs one of those
 * <b>renders</b> the commands here; the process exits with
 * {@link dev.watchwolf.cli.ExitCodes#HOST_ACTION_REQUIRED} and the launcher prints the script in
 * full, asks, and runs it.
 *
 * <p>The user therefore sees every root command before it runs -- which is more honest than a
 * {@code sudo} buried inside a build.
 */
public final class HostAction {
    private final List<String> lines = new ArrayList<>();
    private final List<String> reasons = new ArrayList<>();
    private boolean needsRoot;

    public HostAction add(String reason, String... commands) {
        this.reasons.add(reason);
        this.lines.add("# " + reason);
        this.lines.addAll(List.of(commands));
        this.lines.add("");
        return this;
    }

    public HostAction requiringRoot() {
        this.needsRoot = true;
        return this;
    }

    public boolean isEmpty()          { return this.lines.isEmpty(); }
    public boolean needsRoot()        { return this.needsRoot; }
    public List<String> reasons()     { return List.copyOf(this.reasons); }

    public String script() {
        StringBuilder script = new StringBuilder();
        script.append("#!/usr/bin/env bash\n");
        script.append("# Written by 'watchwolf'. Review before running.\n");
        script.append("set -euo pipefail\n\n");
        this.lines.forEach(line -> script.append(line).append('\n'));
        return script.toString();
    }

    /** Shell-quotes a path so a base directory containing spaces cannot break the script. */
    public static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
