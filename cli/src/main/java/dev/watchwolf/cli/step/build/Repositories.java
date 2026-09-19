package dev.watchwolf.cli.step.build;

/** The repositories an install clones, and the files that prove a clone really landed. */
public final class Repositories {

    public record Descriptor(String url, String directoryName, String[] expectedFiles) { }

    public static final Descriptor SERVERS_MANAGER = new Descriptor(
            "https://github.com/rogermiranda1000/WatchWolf-ServersManager.git",
            "ServersManager",
            new String[] {
                    "ci/release/build.sh",
                    "ci/release/run.sh",
                    "ci/release/docker-compose.yml",
                    "ci/release/Dockerfile",
                    "src/tools/SpigotBuilder.sh",
                    "src/tools/PaperBuilder.sh",
            });

    public static final Descriptor CLIENTS_MANAGER = new Descriptor(
            "https://github.com/rogermiranda1000/WatchWolf-Client.git",
            "ClientsManager",
            new String[] {
                    "Dockerfile",
                    "ClientsManager.py",
                    "requirements.txt",
            });

    public static final Descriptor TESTER = new Descriptor(
            "https://github.com/rogermiranda1000/WatchWolf-Tester.git",
            "WatchWolf-Tester",
            new String[] {
                    "ci/tests.sh",
                    "pom.xml",
            });

    private Repositories() {
    }
}
