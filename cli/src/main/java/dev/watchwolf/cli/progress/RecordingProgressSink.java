package dev.watchwolf.cli.progress;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures progress events so tests can assert that a slow step announced itself -- and named the
 * host it was waiting on -- without a terminal.
 */
public final class RecordingProgressSink implements ProgressSink {
    public record Event(String kind, String text) { }

    private final List<Event> events = new ArrayList<>();

    @Override public void begin(String what)  { this.events.add(new Event("begin", what)); }
    @Override public void end(String outcome) { this.events.add(new Event("end", outcome)); }
    @Override public void warn(String message) { this.events.add(new Event("warn", message)); }
    @Override public void detail(String message) { this.events.add(new Event("detail", message)); }

    @Override
    public void update(String detail, long done, long total) {
        this.events.add(new Event("update", detail));
    }

    public List<Event> events() {
        return List.copyOf(this.events);
    }

    public List<String> textOf(String kind) {
        return this.events.stream().filter(e -> e.kind().equals(kind)).map(Event::text).toList();
    }

    public boolean announcedSomethingContaining(String fragment) {
        return this.events.stream()
                .anyMatch(e -> e.text() != null && e.text().contains(fragment));
    }
}
