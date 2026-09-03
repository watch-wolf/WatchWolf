package dev.watchwolf.cli.model;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A jar in {@code usual-plugins/}, named {@code <Name>-<pluginVersion>-<minMc>-<maxMc>.jar}.
 *
 * <p>The pattern below is <b>the ServersManager's own</b> -- copied from
 * {@code ServersManagerPluginDeserializer}, which is what actually decides whether a plugin can be
 * loaded. Validating against anything looser would let the installer accept a name the runtime then
 * silently ignores, which is precisely the failure the old {@code cp}-by-hand flow produced.
 *
 * <p>Examples: {@code WorldGuard-7.0.8-1.19-LATEST.jar}, {@code WatchWolf-0.3-1.8-LATEST.jar},
 * {@code WorldEdit-6.1.9-1.8-1.12.2.jar}. Spaces in a plugin name become {@code _}.
 */
public final class UsualPluginJar {
    /** @see <a href="ServersManagerPluginDeserializer.java">the ServersManager's copy</a> */
    public static final Pattern FILENAME =
            Pattern.compile("^([^-]+)-([\\d.]+)-([\\d.]+)-((\\d+(\\.\\d+)*)|(LATEST))\\.jar$");

    private final String fileName;
    private final String name;
    private final String pluginVersion;
    private final McVersion minMcVersion;
    private final McVersion maxMcVersion;

    private UsualPluginJar(String fileName, String name, String pluginVersion,
                           McVersion minMcVersion, McVersion maxMcVersion) {
        this.fileName = fileName;
        this.name = name;
        this.pluginVersion = pluginVersion;
        this.minMcVersion = minMcVersion;
        this.maxMcVersion = maxMcVersion;
    }

    /** @throws IllegalArgumentException when the ServersManager could not load this name */
    public static UsualPluginJar parse(String fileName) {
        UsualPluginJar parsed = parseOrNull(fileName);
        if (parsed == null) {
            throw new IllegalArgumentException(
                    "'" + fileName + "' is not a name the ServersManager can load. Expected "
                            + "<Name>-<pluginVersion>-<minMcVersion>-<maxMcVersion>.jar, "
                            + "e.g. WorldGuard-7.0.8-1.19-LATEST.jar");
        }
        return parsed;
    }

    public static UsualPluginJar parseOrNull(String fileName) {
        if (fileName == null) return null;
        Matcher matcher = FILENAME.matcher(fileName);
        if (!matcher.matches()) return null;

        McVersion min = McVersion.parseOrNull(matcher.group(3));
        McVersion max = McVersion.parseOrNull(matcher.group(4));
        if (min == null || max == null) return null;

        return new UsualPluginJar(fileName, matcher.group(1), matcher.group(2), min, max);
    }

    public static boolean isValidName(String fileName) {
        return parseOrNull(fileName) != null;
    }

    /** Builds the name from its parts, so nothing has to be renamed by hand. */
    public static String fileNameFor(String name, String pluginVersion,
                                     McVersion minMcVersion, McVersion maxMcVersion) {
        return name.replace(' ', '_') + "-" + pluginVersion
                + "-" + minMcVersion + "-" + maxMcVersion + ".jar";
    }

    public String fileName()          { return this.fileName; }
    public String name()              { return this.name; }
    public String pluginVersion()     { return this.pluginVersion; }
    public McVersion minMcVersion()   { return this.minMcVersion; }
    public McVersion maxMcVersion()   { return this.maxMcVersion; }

    /** Is this jar declared to work on {@code mcVersion}? */
    public boolean supports(McVersion mcVersion) {
        return mcVersion.isAtLeast(this.minMcVersion) && this.maxMcVersion.isAtLeast(mcVersion);
    }

    public boolean isWatchWolfServer() {
        return this.name.equalsIgnoreCase("WatchWolf");
    }

    @Override
    public String toString() {
        return this.fileName;
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof UsualPluginJar) && this.fileName.equals(((UsualPluginJar) o).fileName);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(this.fileName);
    }
}
