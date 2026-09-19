package dev.watchwolf.cli.tui.monitor;

import java.util.List;

/**
 * The level-2 view of one entity: its facts, and where its log comes from.
 *
 * <p>{@link #logSource()} is deliberately explicit. For a Minecraft server it is the
 * <b>file</b> {@code logs/<id>/latest.log}, not {@code docker logs}: those containers run with
 * {@code --autoRemove}, so Docker discards their output the instant the server stops, while the
 * file survives. Reading the wrong one is how a finished run looks like it produced nothing.
 *
 * <p>{@link #logIsLive()} answers a different question from "is there a log source at all"
 * ({@link LogSource.None} already covers that): whether the source can still produce <em>new</em>
 * lines. A finished MC server's {@code latest.log} still exists and reads fine, but nothing will
 * ever be appended to it again -- "following" it is meaningless, and the screen should not offer
 * or claim to do it. {@code false} for a stopped manager or a finished server; {@code true} for a
 * bot, which by construction is only shown while its port is still listening.
 */
public record EntityView(String title, List<String> facts, LogSource logSource,
                         String unavailableReason, boolean logIsLive) {

    /** Where this entity's output lives. */
    public sealed interface LogSource {
        /** A container's stdout, followed live. */
        record ContainerLog(String containerName) implements LogSource { }

        /** A file on disk -- survives the container. */
        record FileLog(java.nio.file.Path path) implements LogSource { }

        /** The ClientsManager's stream, filtered to one bot's line prefix. */
        record FilteredContainerLog(String containerName, String linePrefix) implements LogSource { }

        /** Nothing to show, with a reason. */
        record None(String why) implements LogSource { }
    }
}
