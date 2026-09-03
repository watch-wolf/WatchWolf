package dev.watchwolf.cli.remote;

import dev.watchwolf.cli.progress.ProgressSink;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * {@link HttpFetcher} over the JDK client.
 *
 * <p>Every call is bounded and retried, and both facts are reported. The behaviour being replaced
 * is {@code run.sh}'s bare {@code curl ifconfig.me}, which hangs the whole startup when the machine
 * is offline.
 */
public final class JdkHttpFetcher implements HttpFetcher {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(20);
    private static final int ATTEMPTS = 3;

    private final HttpClient client;

    public JdkHttpFetcher() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String getString(String url, ProgressSink progress) {
        String host = hostOf(url);
        RuntimeException last = null;

        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = this.client.send(
                        HttpRequest.newBuilder(URI.create(url))
                                .timeout(REQUEST_TIMEOUT)
                                .header("User-Agent", "watchwolf-cli")
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (response.statusCode() / 100 == 2) return response.body();

                last = new FetchFailedException(host,
                        host + " answered HTTP " + response.statusCode() + " for " + url,
                        "Check the URL is still correct, and that the service is up.", null);
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                last = new FetchFailedException(host,
                        host + " did not answer within " + REQUEST_TIMEOUT.toSeconds() + "s",
                        "Check this machine's network access to " + host + ".", ex);
            }

            if (attempt < ATTEMPTS) {
                progress.warn(host + " did not answer; retrying (" + (attempt + 1)
                        + "/" + ATTEMPTS + ")");
                sleepBackoff(attempt);
            }
        }
        throw last;
    }

    @Override
    public void download(String url, Path destination, ProgressSink progress) {
        String host = hostOf(url);
        progress.begin("Downloading " + destination.getFileName() + " from " + host);

        // stage next to the destination and rename on success: an interrupted download must never
        // leave a truncated jar behind, because that only fails much later at server start
        Path staging = destination.resolveSibling(destination.getFileName() + ".part");
        try {
            Files.createDirectories(destination.getParent());
            Files.deleteIfExists(staging);

            HttpResponse<InputStream> response = this.client.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(DOWNLOAD_TIMEOUT)
                            .header("User-Agent", "watchwolf-cli")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() / 100 != 2) {
                throw new FetchFailedException(host,
                        host + " answered HTTP " + response.statusCode() + " for " + url,
                        "Check the URL is still correct.", null);
            }

            long expected = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            long written = 0;
            long lastReport = System.nanoTime();

            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(staging)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                    written += read;
                    // heartbeat at least every couple of seconds, so a stall is visibly a stall
                    if (System.nanoTime() - lastReport > 2_000_000_000L) {
                        progress.update(human(written) + (expected > 0 ? "/" + human(expected) : ""),
                                written, expected);
                        lastReport = System.nanoTime();
                    }
                }
            }

            if (expected > 0 && written != expected) {
                throw new FetchFailedException(host,
                        "the download stopped early (" + written + " of " + expected + " bytes)",
                        "Re-run the command; the partial file was discarded.", null);
            }

            Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING);
            progress.end("downloaded " + human(written));

        } catch (FetchFailedException ex) {
            discard(staging);
            progress.end("failed");
            throw ex;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            discard(staging);
            progress.end("failed");
            throw new FetchFailedException(host,
                    "could not download from " + host + ": " + ex.getMessage(),
                    "Check this machine's network access to " + host + ".", ex);
        }
    }

    private static void discard(Path staging) {
        try {
            Files.deleteIfExists(staging);
        } catch (IOException ignored) {
            // best effort; the .part name makes it obvious what it is
        }
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(500L * attempt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host;
        } catch (RuntimeException ex) {
            return url;
        }
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return Math.round(bytes / 1024.0) + "KB";
        return String.format("%.1fMB", bytes / (1024.0 * 1024));
    }
}
