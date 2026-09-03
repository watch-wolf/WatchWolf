package dev.watchwolf.cli.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;

/** {@link FileGateway} over {@code java.nio.file}. */
public final class NioFileGateway implements FileGateway {

    @Override
    public boolean exists(Path path) {
        return Files.exists(path);
    }

    @Override
    public boolean isDirectory(Path path) {
        return Files.isDirectory(path);
    }

    @Override
    public boolean isReadable(Path path) {
        return Files.isReadable(path);
    }

    @Override
    public boolean isWritable(Path path) {
        return Files.isWritable(path);
    }

    @Override
    public List<Path> list(Path directory) {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.sorted().toList();
        } catch (IOException ex) {
            return List.of();       // unreadable (often root-owned); callers report it as skipped
        }
    }

    @Override
    public String readString(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Override
    public List<String> readLastLines(Path path, int maxLines) throws IOException {
        Deque<String> tail = new ArrayDeque<>(Math.max(maxLines, 1));
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.size() == maxLines) tail.removeFirst();
                tail.addLast(line);
            }
        } catch (java.nio.charset.MalformedInputException ex) {
            // a server log can carry non-UTF-8 bytes; degrade rather than fail the whole view
            return List.of("[the log is not valid UTF-8 from this point on]");
        }
        return new ArrayList<>(tail);
    }

    @Override
    public InputStream open(Path path) throws IOException {
        return Files.newInputStream(path);
    }

    @Override
    public long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            return -1;
        }
    }

    @Override
    public Instant lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException ex) {
            return null;
        }
    }

    @Override
    public void createDirectories(Path path) throws IOException {
        Files.createDirectories(path);
    }

    @Override
    public void writeString(Path path, String contents) throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(path, contents, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    @Override
    public void setExecutable(Path path) throws IOException {
        if (!path.toFile().setExecutable(true, false)) {
            throw new IOException("Could not make " + path + " executable");
        }
    }

    @Override
    public void delete(Path path) throws IOException {
        Files.deleteIfExists(path);
    }
}
