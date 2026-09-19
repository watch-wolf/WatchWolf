package dev.watchwolf.cli.docker;

/**
 * The daemon could not be reached or refused an operation.
 *
 * <p>Carries a remedy, because "connection refused" on its own has never helped anybody.
 */
public class DockerUnavailableException extends RuntimeException {
    private final String remedy;

    public DockerUnavailableException(String message, String remedy) {
        super(message);
        this.remedy = remedy;
    }

    public DockerUnavailableException(String message, String remedy, Throwable cause) {
        super(message, cause);
        this.remedy = remedy;
    }

    public String remedy() {
        return this.remedy;
    }
}
