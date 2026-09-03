package dev.watchwolf.cli.parse;

import dev.watchwolf.cli.model.ClientLogEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises the meaningful shapes in the ClientsManager's stdout.
 *
 * <p>This log is the <b>only</b> source of client identity. Bots are not containers -- they are
 * Python objects and threads inside the single {@code ClientsManager} container -- so there is no
 * Docker object to inspect and no per-client file on disk. (Do not look for
 * {@code logs/clients_manager.log}: the line that would write it is commented out, so it never
 * exists.)
 *
 * <p>The two shapes carry different facts, and conflating them is the easy mistake:
 * the {@code [user - host:port]} prefix carries the <b>Minecraft server's</b> address, while the
 * bot's own assigned port appears only in {@code Client started at <ip>:<port>}.
 *
 * @see ClientsManagerLogReader for the adjacency rule that pairs a username to a port
 */
public final class ClientsManagerLogParser {
    /** {@code [MinecraftGamer_Z - 127.0.0.1:8001] Breaking block at ...} */
    private static final Pattern CLIENT_MESSAGE =
            Pattern.compile("^\\[(?<user>.+?) - (?<host>[^\\s:\\]]+):(?<port>\\d+)] ?(?<msg>.*)$");

    /** {@code Starting client MinecraftGamer_Z at server 127.0.0.1:8001...} */
    private static final Pattern CLIENT_STARTING =
            Pattern.compile("^Starting client (?<user>\\S+) at server (?<addr>\\S+?)\\.*$");

    /** {@code Client started at 192.168.1.5:7001} */
    private static final Pattern CLIENT_STARTED =
            Pattern.compile("^Client started at (?<host>[^\\s:]+):(?<port>\\d+)\\s*$");

    private static final Pattern CLIENT_DISCONNECTED =
            Pattern.compile("^\\[i] Client disconnected.*$");

    private ClientsManagerLogParser() {
    }

    /** {@code null} when the line carries nothing we can use. */
    public static ClientLogEvent parseLine(String line) {
        if (line == null) return null;
        String trimmed = line.strip();
        if (trimmed.isEmpty()) return null;

        Matcher message = CLIENT_MESSAGE.matcher(trimmed);
        if (message.matches()) {
            return new ClientLogEvent(
                    ClientLogEvent.Kind.CLIENT_MESSAGE,
                    message.group("user"),
                    message.group("host"),
                    Integer.parseInt(message.group("port")),   // the MINECRAFT server's port
                    message.group("msg"),
                    trimmed);
        }

        Matcher starting = CLIENT_STARTING.matcher(trimmed);
        if (starting.matches()) {
            return new ClientLogEvent(
                    ClientLogEvent.Kind.CLIENT_STARTING,
                    starting.group("user"), null, null, starting.group("addr"), trimmed);
        }

        Matcher started = CLIENT_STARTED.matcher(trimmed);
        if (started.matches()) {
            return new ClientLogEvent(
                    ClientLogEvent.Kind.CLIENT_STARTED,
                    null,                                       // this line never names the user
                    started.group("host"),
                    Integer.parseInt(started.group("port")),    // the BOT's own assigned port
                    null, trimmed);
        }

        if (CLIENT_DISCONNECTED.matcher(trimmed).matches()) {
            return new ClientLogEvent(
                    ClientLogEvent.Kind.CLIENT_DISCONNECTED, null, null, null, null, trimmed);
        }

        return null;
    }
}
