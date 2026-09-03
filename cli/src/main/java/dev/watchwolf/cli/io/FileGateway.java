package dev.watchwolf.cli.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Filesystem access, behind a seam.
 *
 * <p>Two reasons this is not just {@code java.nio.file.Files}. First, tests need to simulate the
 * ServersManager's {@code logs/} and {@code tmp/} being <b>root-owned</b> -- they are, because the
 * ServersManager container writes them as root -- and an {@code AccessDeniedException} is otherwise
 * awkward to provoke. Second, it keeps the code checks able to assert that no I/O leaks into the
 * pure model and parsing layers.
 */
public interface FileGateway {

    boolean exists(Path path);

    boolean isDirectory(Path path);

    boolean isReadable(Path path);

    boolean isWritable(Path path);

    /** Entries of a directory, sorted by name. Empty when it does not exist or is unreadable. */
    List<Path> list(Path directory);

    String readString(Path path) throws IOException;

    /** The last {@code maxLines} lines, for tailing a log without loading all of it. */
    List<String> readLastLines(Path path, int maxLines) throws IOException;

    InputStream open(Path path) throws IOException;

    long size(Path path);

    Instant lastModified(Path path);

    void createDirectories(Path path) throws IOException;

    void writeString(Path path, String contents) throws IOException;

    void setExecutable(Path path) throws IOException;

    void delete(Path path) throws IOException;
}
