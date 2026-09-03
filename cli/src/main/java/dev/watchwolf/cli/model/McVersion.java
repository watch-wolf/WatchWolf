package dev.watchwolf.cli.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Minecraft version such as {@code 1.8.8}, {@code 1.20}, or the sentinel {@code LATEST}.
 *
 * <p>Compared component-wise and numerically, so {@code 1.9} &gt; {@code 1.10} is false and
 * {@code 1.20.5} &gt; {@code 1.20.4} is true -- which plain string ordering gets wrong both times.
 * Missing components read as zero, so {@code 1.20} equals {@code 1.20.0}.
 */
public final class McVersion implements Comparable<McVersion> {
    private static final Pattern VERSION = Pattern.compile("^\\d+(\\.\\d+)*$");

    /** Upper bound meaning "every version from here on", as used in usual-plugin filenames. */
    public static final McVersion LATEST = new McVersion(null);

    private final List<Integer> components;

    private McVersion(List<Integer> components) {
        this.components = components;
    }

    public static McVersion of(String raw) {
        String trimmed = Objects.requireNonNull(raw, "raw").trim();
        if (trimmed.equalsIgnoreCase("LATEST")) return LATEST;
        if (!VERSION.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Not a Minecraft version: '" + raw + "'");
        }
        List<Integer> parsed = new ArrayList<>();
        for (String part : trimmed.split("\\.")) parsed.add(Integer.parseInt(part));
        return new McVersion(List.copyOf(parsed));
    }

    /** {@code null} when the text is not a version, instead of throwing. */
    public static McVersion parseOrNull(String raw) {
        try {
            return of(raw);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public boolean isLatest() {
        return this.components == null;
    }

    private int componentAt(int index) {
        return index < this.components.size() ? this.components.get(index) : 0;
    }

    @Override
    public int compareTo(McVersion other) {
        if (this.isLatest() && other.isLatest()) return 0;
        if (this.isLatest()) return 1;   // LATEST is above everything
        if (other.isLatest()) return -1;

        int width = Math.max(this.components.size(), other.components.size());
        for (int i = 0; i < width; i++) {
            int diff = Integer.compare(this.componentAt(i), other.componentAt(i));
            if (diff != 0) return diff;
        }
        return 0;
    }

    public boolean isAtLeast(McVersion other) {
        return this.compareTo(other) >= 0;
    }

    public boolean isBelow(McVersion other) {
        return this.compareTo(other) < 0;
    }

    @Override
    public String toString() {
        if (this.isLatest()) return "LATEST";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < this.components.size(); i++) {
            if (i > 0) out.append('.');
            out.append(this.components.get(i));
        }
        return out.toString();
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof McVersion) && this.compareTo((McVersion) o) == 0;
    }

    @Override
    public int hashCode() {
        // must agree with equals, which ignores trailing zeroes
        if (this.isLatest()) return Integer.MAX_VALUE;
        int trimmed = this.components.size();
        while (trimmed > 0 && this.components.get(trimmed - 1) == 0) trimmed--;
        return this.components.subList(0, trimmed).hashCode();
    }

    /** Helper for the callers that hold a raw string, e.g. an {@code info.txt} field. */
    public static Matcher matcherFor(String text, Pattern pattern) {
        return pattern.matcher(text);
    }
}
