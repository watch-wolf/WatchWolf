package dev.watchwolf.cli.tui.monitor;

import dev.watchwolf.cli.inventory.EnvironmentScanner;
import dev.watchwolf.cli.inventory.EnvironmentSnapshot;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Polls the environment on its own thread and publishes immutable snapshots.
 *
 * <p>The draw loop only ever reads the {@link AtomicReference}, so rendering never blocks on
 * Docker. That matters because a poll involves several daemon round-trips plus a
 * {@code docker exec}, and a frozen dashboard is worse than a slightly stale one.
 *
 * <p>The {@code exec} for client discovery is throttled: it forks a process inside the
 * ClientsManager container, and doing that every two seconds is needlessly hot.
 */
public final class MonitorPoller implements AutoCloseable {
    private static final int EXEC_EVERY_NTH_POLL = 3;

    private final EnvironmentScanner scanner;
    private final Duration interval;
    private final AtomicReference<EnvironmentSnapshot> latest = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread thread;

    public MonitorPoller(EnvironmentScanner scanner, Duration interval) {
        this.scanner = scanner;
        this.interval = interval;
    }

    /** Blocks for the first snapshot, so the screen never paints an empty frame. */
    public EnvironmentSnapshot start() {
        EnvironmentSnapshot first = this.scanner.scan();
        this.latest.set(first);

        this.thread = new Thread(this::pollLoop, "watchwolf-monitor-poller");
        this.thread.setDaemon(true);
        this.thread.start();
        return first;
    }

    private void pollLoop() {
        int poll = 0;
        while (this.running.get()) {
            try {
                Thread.sleep(this.interval.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }

            poll++;
            try {
                // stats are the slowest part of a poll; take them on the cheap polls only
                this.latest.set(this.scanner
                        .collectingStats(poll % EXEC_EVERY_NTH_POLL == 0)
                        .scan());
                this.lastError.set(null);
            } catch (RuntimeException ex) {
                // keep the last good snapshot on screen and say so, rather than blanking
                this.lastError.set(ex.getMessage() == null
                        ? ex.getClass().getSimpleName() : ex.getMessage());
            }
        }
    }

    public EnvironmentSnapshot latest() {
        return this.latest.get();
    }

    public String lastError() {
        return this.lastError.get();
    }

    @Override
    public void close() {
        this.running.set(false);
        if (this.thread != null) this.thread.interrupt();
    }
}
