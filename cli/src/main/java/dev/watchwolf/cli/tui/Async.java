package dev.watchwolf.cli.tui;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * A value being fetched in the background, with all four states the UI must render.
 *
 * <p>This exists so a menu never goes white and unresponsive while the network answers. Opening
 * "Server jars" needs version lists from hub.spigotmc.org and api.papermc.io, which takes seconds;
 * the screen paints {@link State#LOADING} with a spinner and an elapsed count, stays navigable, and
 * lets Escape cancel -- rather than freezing until the request returns.
 *
 * <p>Immutable and free of any terminal code, so the behaviour is unit-testable.
 */
public final class Async<T> {
    public enum State { NOT_STARTED, LOADING, LOADED, FAILED }

    private final State state;
    private final T value;
    private final Instant startedAt;
    private final String failureDetail;
    private final String failureRemedy;

    private Async(State state, T value, Instant startedAt,
                  String failureDetail, String failureRemedy) {
        this.state = state;
        this.value = value;
        this.startedAt = startedAt;
        this.failureDetail = failureDetail;
        this.failureRemedy = failureRemedy;
    }

    public static <T> Async<T> notStarted() {
        return new Async<>(State.NOT_STARTED, null, null, null, null);
    }

    public static <T> Async<T> loading(Instant startedAt) {
        return new Async<>(State.LOADING, null, startedAt, null, null);
    }

    public static <T> Async<T> loaded(T value) {
        return new Async<>(State.LOADED, value, null, null, null);
    }

    public static <T> Async<T> failed(String detail, String remedy) {
        return new Async<>(State.FAILED, null, null, detail, remedy);
    }

    public State state()                 { return this.state; }
    public boolean isLoading()           { return this.state == State.LOADING; }
    public boolean isLoaded()            { return this.state == State.LOADED; }
    public boolean hasFailed()           { return this.state == State.FAILED; }
    public Optional<T> value()           { return Optional.ofNullable(this.value); }
    public Optional<String> failureDetail() { return Optional.ofNullable(this.failureDetail); }
    public Optional<String> failureRemedy() { return Optional.ofNullable(this.failureRemedy); }

    public Duration elapsed(Instant now) {
        return this.startedAt == null ? Duration.ZERO : Duration.between(this.startedAt, now);
    }

    /** What the pane shows while it waits -- always naming the host it is waiting on. */
    public String describe(String whatAndHost, Instant now) {
        return switch (this.state) {
            case NOT_STARTED -> whatAndHost + "...";
            case LOADING -> whatAndHost + "... (" + this.elapsed(now).toSeconds() + "s)";
            case LOADED -> "";
            case FAILED -> this.failureDetail;
        };
    }
}
