package dev.watchwolf.cli.tui.monitor;

import dev.watchwolf.cli.model.Confidence;

/**
 * One line of the overview's tree.
 *
 * <p>Note what is <b>not</b> here: log lines. The overview is an inventory; logs belong to one
 * entity and only appear once you enter it.
 */
public record MonitorRow(Kind kind, String key, String name, String type, String version,
                         String ports, String state, String uptime, Confidence confidence,
                         int depth, boolean selectable) {

    public enum Kind { MANAGER, SERVER, CLIENT, NOTE }

    public static MonitorRow note(String text, int depth) {
        return new MonitorRow(Kind.NOTE, null, text, "", "", "", "", "",
                Confidence.UNKNOWN, depth, false);
    }

    public String indentedName() {
        return "  ".repeat(Math.max(0, this.depth)) + this.name;
    }
}
