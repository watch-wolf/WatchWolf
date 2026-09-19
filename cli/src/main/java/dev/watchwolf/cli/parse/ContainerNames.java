package dev.watchwolf.cli.parse;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The container names WatchWolf uses, and the ids embedded in them.
 *
 * <p>{@code MC_Server-<millis>} is built by {@code DockerizedServerInstantiator} from the trailing
 * digits of the server's scratch folder, which makes {@code <millis>} the join key to both
 * {@code tmp/<millis>/} and {@code logs/<millis>/}.
 */
public final class ContainerNames {
    public static final String SERVERS_MANAGER = "ServersManager";
    public static final String CLIENTS_MANAGER = "ClientsManager";
    public static final String MC_SERVER_PREFIX = "MC_Server-";
    public static final String MC_SERVER_FILTER = "MC_Server-*";
    public static final String SPIGOT_BUILDER_PREFIX = "Spigot_build_";

    private static final Pattern MC_SERVER = Pattern.compile("^/?MC_Server-(?<id>\\d+)$");
    private static final Pattern SPIGOT_BUILDER = Pattern.compile("^/?Spigot_build_(?<version>.+)$");

    private ContainerNames() {
    }

    /**
     * Docker reports names with a leading slash ({@code /ServersManager}).
     *
     * <p>Worth knowing: the ServersManager compares its own ids against those raw names, so its
     * {@code closeAllLaunchedServers} never matches anything. We normalise instead.
     */
    public static String normalise(String dockerName) {
        if (dockerName == null) return null;
        return dockerName.startsWith("/") ? dockerName.substring(1) : dockerName;
    }

    public static Optional<String> mcServerSessionId(String containerName) {
        if (containerName == null) return Optional.empty();
        Matcher matcher = MC_SERVER.matcher(containerName);
        return matcher.matches() ? Optional.of(matcher.group("id")) : Optional.empty();
    }

    public static boolean isMcServer(String containerName) {
        return mcServerSessionId(containerName).isPresent();
    }

    public static String mcServerFor(String sessionId) {
        return MC_SERVER_PREFIX + sessionId;
    }

    public static Optional<String> spigotBuilderVersion(String containerName) {
        if (containerName == null) return Optional.empty();
        Matcher matcher = SPIGOT_BUILDER.matcher(containerName);
        return matcher.matches() ? Optional.of(matcher.group("version")) : Optional.empty();
    }

    public static String spigotBuilderFor(String version) {
        return SPIGOT_BUILDER_PREFIX + version;
    }
}
