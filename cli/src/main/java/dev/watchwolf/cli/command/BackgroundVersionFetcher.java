package dev.watchwolf.cli.command;

import dev.watchwolf.cli.model.McVersion;
import dev.watchwolf.cli.progress.ProgressSink;
import dev.watchwolf.cli.remote.HttpFetcher;
import dev.watchwolf.cli.remote.PaperApiClient;
import dev.watchwolf.cli.remote.SpigotHubClient;
import dev.watchwolf.cli.tui.Async;
import dev.watchwolf.cli.tui.menu.MenuConfigScreen;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Fetches the two remote version lists off the UI thread.
 *
 * <p>This is what stops the menu going white for several seconds when someone opens "Server jars".
 * The screen renders whichever {@link Async} state is current; this only hands it the next one.
 */
final class BackgroundVersionFetcher implements MenuConfigScreen.VersionFetcher {
    private final HttpFetcher http;
    private final ExecutorService executor =
            Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "watchwolf-version-fetch");
                thread.setDaemon(true);
                return thread;
            });

    BackgroundVersionFetcher(HttpFetcher http) {
        this.http = http;
    }

    @Override
    public void fetchSpigot(Consumer<Async<List<McVersion>>> onState) {
        this.executor.submit(() -> {
            try {
                onState.accept(Async.loaded(
                        new SpigotHubClient(this.http).availableVersions(ProgressSink.discarding())));
            } catch (HttpFetcher.FetchFailedException ex) {
                onState.accept(Async.failed(ex.getMessage(),
                        "Versions already on disk are still selectable; pass "
                                + "--skip-spigot-build to skip Spigot entirely."));
            } catch (RuntimeException ex) {
                onState.accept(Async.failed("hub.spigotmc.org: " + ex.getMessage(),
                        "Versions already on disk are still selectable."));
            }
        });
    }

    @Override
    public void fetchPaper(Consumer<Async<List<McVersion>>> onState) {
        this.executor.submit(() -> {
            try {
                onState.accept(Async.loaded(
                        new PaperApiClient(this.http).availableVersions(ProgressSink.discarding())));
            } catch (HttpFetcher.FetchFailedException ex) {
                onState.accept(Async.failed(ex.getMessage(),
                        "Versions already on disk are still selectable."));
            } catch (RuntimeException ex) {
                onState.accept(Async.failed("api.papermc.io: " + ex.getMessage(),
                        "Versions already on disk are still selectable."));
            }
        });
    }

    @Override
    public void cancel() {
        this.executor.shutdownNow();
    }
}
