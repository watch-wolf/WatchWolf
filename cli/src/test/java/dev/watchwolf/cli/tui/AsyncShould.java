package dev.watchwolf.cli.tui;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AsyncShould {
    @Test
    public void distinguishNotStartedFromLoading() {
        assertEquals(Async.State.NOT_STARTED, Async.notStarted().state());
        assertTrue(Async.loading(Instant.now()).isLoading());
    }

    @Test
    public void reportHowLongItHasBeenWaiting() {
        Instant started = Instant.parse("2026-09-03T12:00:00Z");
        Async<String> loading = Async.loading(started);

        assertEquals(7, loading.elapsed(Instant.parse("2026-09-03T12:00:07Z")).toSeconds());
    }

    @Test
    public void nameTheHostItIsWaitingOn() {
        // "polling..." is useless when the question is which host is down
        Async<List<String>> loading = Async.loading(Instant.parse("2026-09-03T12:00:00Z"));

        assertEquals("Polling Spigot versions from hub.spigotmc.org... (3s)",
                loading.describe("Polling Spigot versions from hub.spigotmc.org",
                        Instant.parse("2026-09-03T12:00:03Z")));
    }

    @Test
    public void carryBothTheCauseAndTheRemedyOnFailure() {
        Async<String> failed = Async.failed("hub.spigotmc.org unreachable",
                "Locally present versions are still selectable.");

        assertTrue(failed.hasFailed());
        assertEquals("hub.spigotmc.org unreachable", failed.failureDetail().orElseThrow());
        assertTrue(failed.failureRemedy().orElseThrow().contains("still selectable"));
        assertTrue(failed.value().isEmpty());
    }

    @Test
    public void holdTheValueOnceLoaded() {
        Async<List<String>> loaded = Async.loaded(List.of("1.20.4"));
        assertTrue(loaded.isLoaded());
        assertEquals(List.of("1.20.4"), loaded.value().orElseThrow());
    }
}
