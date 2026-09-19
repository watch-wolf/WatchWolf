package dev.watchwolf.cli.inventory;

import dev.watchwolf.cli.io.FileGateway;
import dev.watchwolf.cli.layout.InstallLayout;
import dev.watchwolf.cli.model.McVersion;
import dev.watchwolf.cli.model.ServerTypeVersion;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What is actually installed under {@code server-types/}.
 *
 * <p>Read from the filesystem rather than from the build plan, because the two can differ: a
 * previous install, a hand-dropped jar, or a custom server type the user added themselves. Any
 * folder name is a valid type -- {@code CustomSpigot/1.20.4.jar} works -- so nothing here assumes
 * Spigot or Paper.
 */
public final class ServerJarInventory {
    private final FileGateway files;
    private final InstallLayout layout;

    public ServerJarInventory(FileGateway files, InstallLayout layout) {
        this.files = files;
        this.layout = layout;
    }

    public Set<ServerTypeVersion> installed() {
        Set<ServerTypeVersion> installed = new LinkedHashSet<>();
        for (Path typeDirectory : this.files.list(this.layout.serverTypes())) {
            if (!this.files.isDirectory(typeDirectory)) continue;
            String type = typeDirectory.getFileName().toString();

            for (Path jar : this.files.list(typeDirectory)) {
                String name = jar.getFileName().toString();
                if (!name.endsWith(".jar")) continue;
                McVersion version = McVersion.parseOrNull(name.substring(0, name.length() - 4));
                if (version != null) installed.add(new ServerTypeVersion(type, version));
            }
        }
        return installed;
    }

    /** Just the Minecraft versions, whatever type they are -- what the image checks need. */
    public Set<McVersion> installedVersions() {
        Set<McVersion> versions = new LinkedHashSet<>();
        for (ServerTypeVersion entry : this.installed()) versions.add(entry.version());
        return versions;
    }

    public boolean isEmpty() {
        return this.installed().isEmpty();
    }
}
