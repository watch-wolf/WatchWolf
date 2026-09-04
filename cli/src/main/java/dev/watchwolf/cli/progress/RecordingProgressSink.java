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

    // the per-jar events carry an id as well as text, so they are recorded as "kind:id" -- a test
    // asserting "Spigot 1.8.8 got its own row" needs to tell the rows apart, not just count them

    @Override
    public void taskStarted(String id, String label) {
        this.events.add(new Event("task-started:" + id, label));
    }

    @Override
    public void taskUpdate(String id, String label, String detail, long done, long total) {
        this.events.add(new Event("task-update:" + id, detail));
    }

    @Override
    public void taskFinished(String id, String label, String outcome, boolean succeeded) {
        this.events.add(new Event((succeeded ? "task-ok:" : "task-failed:") + id, outcome));
    }

    public List<String> taskIdsOf(String kind) {
        return this.events.stream()
                .filter(event -> event.kind().startsWith(kind + ":"))
                .map(event -> event.kind().substring(kind.length() + 1))
                .distinct().toList();
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
