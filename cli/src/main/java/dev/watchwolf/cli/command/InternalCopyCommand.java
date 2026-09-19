package dev.watchwolf.cli.command;

import dev.watchwolf.cli.ExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.Callable;

/**
 * Copies a root-owned tree and hands it back owned by the invoking user.
 *
 * <p>Not for people to run: it is the body of the short-lived {@code --user 0} helper container the
 * CLI starts when it needs to read the ServersManager's root-owned {@code logs/} or {@code tmp/}.
 * No {@code sudo} is involved -- the Docker socket is already root-equivalent, so this is the
 * honest way to use the privilege we demonstrably have, rather than asking for a second one.
 */
@Command(name = "internal-copy", hidden = true,
        description = "Internal: copy a tree as root and chown it back to the caller.")
public class InternalCopyCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Source directory.")
    String source;

    @Parameters(index = "1", description = "Destination directory.")
    String destination;

    @Option(names = "--uid", required = true, description = "Uid to give the copy to.")
    int uid;

    @Option(names = "--gid", required = true, description = "Gid to give the copy to.")
    int gid;

    @Override
    public Integer call() throws Exception {
        Path from = Paths.get(this.source);
        Path to = Paths.get(this.destination);

        if (!Files.isDirectory(from)) {
            System.err.println("[e] " + from + " is not a directory.");
            return ExitCodes.ERROR;
        }
        Files.createDirectories(to);

        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                Path target = to.resolve(from.relativize(directory).toString());
                Files.createDirectories(target);
                chown(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Path target = to.resolve(from.relativize(file).toString());
                Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                chown(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) {
                System.err.println("[w] skipped " + file + ": " + failure.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        return ExitCodes.OK;
    }

    /** The point of the whole helper: the output must belong to the user, not to root. */
    private void chown(Path path) {
        try {
            var service = path.getFileSystem().getUserPrincipalLookupService();
            var view = Files.getFileAttributeView(path,
                    java.nio.file.attribute.PosixFileAttributeView.class);
            if (view == null) return;
            view.setOwner(service.lookupPrincipalByName(String.valueOf(this.uid)));
            view.setGroup(service.lookupPrincipalByGroupName(String.valueOf(this.gid)));
        } catch (IOException | RuntimeException ex) {
            System.err.println("[w] could not chown " + path + ": " + ex.getMessage());
        }
    }
}
