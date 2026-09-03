package dev.watchwolf.cli.fake;

import dev.watchwolf.cli.progress.ProgressSink;
import dev.watchwolf.cli.remote.HttpFetcher;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** An in-memory {@link HttpFetcher}: fixed responses keyed by exact URL, no network. */
public final class FakeHttpFetcher implements HttpFetcher {
    private final Map<String, String> responses = new LinkedHashMap<>();
    private final Map<String, RuntimeException> failures = new LinkedHashMap<>();

    public FakeHttpFetcher respondTo(String url, String body) {
        this.responses.put(url, body);
        return this;
    }

    public FakeHttpFetcher failFor(String url, RuntimeException failure) {
        this.failures.put(url, failure);
        return this;
    }

    @Override
    public String getString(String url, ProgressSink progress) {
        if (this.failures.containsKey(url)) throw this.failures.get(url);
        String body = this.responses.get(url);
        if (body == null) {
            throw new FetchFailedException(url, "no fixture registered for " + url,
                    "call respondTo(...) with this exact URL first", null);
        }
        return body;
    }

    @Override
    public void download(String url, Path destination, ProgressSink progress) {
        throw new UnsupportedOperationException("FakeHttpFetcher does not support download() yet");
    }
}
