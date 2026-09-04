package dev.watchwolf.cli.remote;

import dev.watchwolf.cli.progress.ProgressSink;

import java.nio.file.Path;

/**
 * Fetches things over HTTP: the Spigot version index, the PaperMC API, watchwolf.dev's usual-plugin
 * list and its published WatchWolf-Server jars.
 *
 * <p>A seam so the version parsers and download steps can be driven from committed fixtures with
 * no network -- and so a test can make a host time out on purpose and assert the CLI says which
 * host, rather than sitting silent.
 */
public interface HttpFetcher {

    /** @throws FetchFailedException naming the host, never just "request failed" */
    String getString(String url, ProgressSink progress);

    /**
     * Downloads to {@code destination}.
     *
     * <p>Implementations must write to a temporary file and rename on success, so an interrupted
     * download cannot leave a truncated jar that only fails months later at server start.
     */
    void download(String url, Path destination, ProgressSink progress);

    /** A failure that names the host and says what to do. */
    class FetchFailedException extends RuntimeException {
        private final String host;
        private final String remedy;

        public FetchFailedException(String host, String message, String remedy, Throwable cause) {
            super(message, cause);
            this.host = host;
            this.remedy = remedy;
        }

        public String host()   { return this.host; }
        public String remedy() { return this.remedy; }
    }
}
