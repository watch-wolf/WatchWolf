package dev.watchwolf.cli.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Checks that a downloaded or built jar is actually a jar.
 *
 * <p>Existence and non-zero size are not enough. A BuildTools container killed part-way leaves a
 * truncated file; an HTTP error page saved to {@code plugin.jar} is a perfectly good 500-byte file.
 * Both pass "the file is there" and then fail much later, at server start, with something
 * unrecognisable. Opening the zip and looking for the entry that must be present is cheap and
 * catches both.
 */
public final class JarInspector {
    private final FileGateway files;

    public JarInspector(FileGateway files) {
        this.files = files;
    }

    public record Result(boolean valid, String problem, long sizeBytes) {
        public static Result ok(long sizeBytes) {
            return new Result(true, null, sizeBytes);
        }

        public static Result bad(String problem, long sizeBytes) {
            return new Result(false, problem, sizeBytes);
        }
    }

    /** A server jar: a real zip with a {@code Main-Class} in its manifest. */
    public Result inspectServerJar(Path jar, long minimumBytes) {
        Result basics = this.basicChecks(jar, minimumBytes);
        if (!basics.valid()) return basics;

        Optional<String> manifest = this.readEntry(jar, "META-INF/MANIFEST.MF");
        if (manifest.isEmpty()) {
            return Result.bad("it has no META-INF/MANIFEST.MF, so it is not a runnable jar",
                    basics.sizeBytes());
        }
        if (!manifest.get().contains("Main-Class")) {
            return Result.bad("its manifest declares no Main-Class, so 'java -jar' cannot run it",
                    basics.sizeBytes());
        }
        return Result.ok(basics.sizeBytes());
    }

    /** A Bukkit/Spigot plugin: a real zip containing {@code plugin.yml}. */
    public Result inspectPluginJar(Path jar) {
        Result basics = this.basicChecks(jar, 1024);
        if (!basics.valid()) return basics;

        if (this.readEntry(jar, "plugin.yml").isEmpty()) {
            return Result.bad("it contains no plugin.yml, so it is not a Bukkit plugin "
                    + "(an HTTP error page saved as a .jar looks exactly like this)",
                    basics.sizeBytes());
        }
        return Result.ok(basics.sizeBytes());
    }

    private Result basicChecks(Path jar, long minimumBytes) {
        if (!this.files.exists(jar)) {
            return Result.bad("it does not exist", 0);
        }
        long size = this.files.size(jar);
        if (size <= 0) {
            return Result.bad("it is empty", size);
        }
        if (size < minimumBytes) {
            return Result.bad("it is only " + size + " bytes, far below the "
                    + minimumBytes + " expected -- most likely a truncated download or an "
                    + "error page saved as a jar", size);
        }
        if (!this.isZip(jar)) {
            return Result.bad("it is not a valid zip archive", size);
        }
        return Result.ok(size);
    }

    private boolean isZip(Path jar) {
        try (InputStream in = this.files.open(jar)) {
            byte[] header = new byte[4];
            if (in.read(header) != 4) return false;
            // "PK\003\004" -- and PK\005\006 for an (empty) archive
            return header[0] == 'P' && header[1] == 'K'
                    && ((header[2] == 3 && header[3] == 4) || (header[2] == 5 && header[3] == 6));
        } catch (IOException ex) {
            return false;
        }
    }

    private Optional<String> readEntry(Path jar, String entryName) {
        try (ZipInputStream zip = new ZipInputStream(this.files.open(jar))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.getName().equals(entryName)) continue;
                return Optional.of(new String(zip.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException ex) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
