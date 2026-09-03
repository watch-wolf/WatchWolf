package dev.watchwolf.cli.tui.monitor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A bounded, filterable buffer of log lines.
 *
 * <p>Bounded because a chatty server will otherwise fill the heap over a long session, and the
 * dashboard only ever shows a screenful.
 */
public final class LogRing {
    private final int capacity;
    private final Deque<String> lines = new ArrayDeque<>();
    private String filter = "";
    private int scrollBack;

    public LogRing(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void add(String line) {
        if (line == null) return;
        if (this.lines.size() == this.capacity) this.lines.removeFirst();
        this.lines.addLast(line);
    }

    public synchronized void addAll(List<String> lines) {
        lines.forEach(this::add);
    }

    public synchronized void clear() {
        this.lines.clear();
        this.scrollBack = 0;
    }

    public synchronized void setFilter(String filter) {
        this.filter = filter == null ? "" : filter;
        this.scrollBack = 0;
    }

    public String filter() {
        return this.filter;
    }

    public synchronized int size() {
        return this.matching().size();
    }

    /** Scrolling back pins the view; 0 means "follow the tail". */
    public synchronized void scrollBack(int rows) {
        this.scrollBack = Math.max(0, this.scrollBack + rows);
    }

    public synchronized void scrollToTail() {
        this.scrollBack = 0;
    }

    public synchronized boolean isFollowing() {
        return this.scrollBack == 0;
    }

    /** The window of lines to paint, oldest first. */
    public synchronized List<String> window(int height) {
        List<String> matching = this.matching();
        if (matching.isEmpty() || height <= 0) return List.of();

        // Scrolling back further than the buffer holds must stop showing the oldest available
        // line(s), not collapse the window to nothing -- "matching.size() - scrollBack" alone
        // goes negative once scrollBack exceeds the line count, and clamping only at 0 (as a
        // prior version of this did) still yields an empty subList(0, 0) rather than the oldest
        // content actually available.
        int end = Math.max(matching.size() - this.scrollBack, Math.min(height, matching.size()));
        int start = Math.max(0, end - height);
        return new ArrayList<>(matching.subList(start, end));
    }

    private List<String> matching() {
        if (this.filter.isEmpty()) return new ArrayList<>(this.lines);
        List<String> matching = new ArrayList<>();
        String needle = this.filter.toLowerCase();
        for (String line : this.lines) {
            if (line.toLowerCase().contains(needle)) matching.add(line);
        }
        return matching;
    }
}
