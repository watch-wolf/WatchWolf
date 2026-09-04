package dev.watchwolf.cli.layout;

/**
 * Which of the ServersManager's two runtime directories an install uses.
 *
 * <p>WatchWolf-ServersManager ships {@code ci/release} (downloads the published jar) and
 * {@code ci/debug} (compiles locally). Both hold the same {@code server-types/}, {@code usual-plugins/},
 * {@code tmp/} and {@code logs/} contract, so everything downstream only needs to know which one.
 */
public enum RuntimeFlavor {
    RELEASE("release"),
    DEBUG("debug");

    private final String directoryName;

    RuntimeFlavor(String directoryName) {
        this.directoryName = directoryName;
    }

    public String directoryName() {
        return this.directoryName;
    }

    /**
     * The compose project name, which is also what the built image is called
     * ({@code <project>-servers-manager}). Compose defaults the project to the directory name;
     * we always pass it explicitly so an inherited COMPOSE_PROJECT_NAME cannot rename the image.
     */
    public String composeProject() {
        return this.directoryName;
    }

    public String serversManagerImage() {
        return this.directoryName + "-servers-manager:latest";
    }
}
