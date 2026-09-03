package dev.watchwolf.cli.bundle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds {@code manifest.txt}: what was collected, what was skipped, and why.
 *
 * <p>The "why" half is what makes a bundle trustworthy. A missing {@code latest.log} could mean the
 * server never logged, or that the file was root-owned and unreadable -- and those lead to
 * completely different investigations.
 */
public final class ManifestBuilder {
    private record Entry(String name, String detail) { }

    private final Instant collectedAt;
    private final List<Entry> collected = new ArrayList<>();
    private final List<Entry> skipped = new ArrayList<>();
    private final List<String> caveats = new ArrayList<>();

    public ManifestBuilder(Instant collectedAt) {
        this.collectedAt = collectedAt;
    }

    public void collected(String name, String detail) {
        this.collected.add(new Entry(name, detail));
    }

    public void skipped(String name, String why) {
        this.skipped.add(new Entry(name, why));
    }

    public void caveat(String caveat) {
        this.caveats.add(caveat);
    }

    public List<String> collectedNames() {
        return this.collected.stream().map(Entry::name).toList();
    }

    public List<String> skippedNames() {
        return this.skipped.stream().map(Entry::name).toList();
    }

    public String render() {
        StringBuilder text = new StringBuilder();
        text.append("WatchWolf diagnostics bundle\n");
        text.append("collected at ").append(this.collectedAt).append("\n\n");

        if (!this.caveats.isEmpty()) {
            text.append("CAVEATS\n");
            for (String caveat : this.caveats) text.append("  ! ").append(caveat).append('\n');
            text.append('\n');
        }

        text.append("COLLECTED (").append(this.collected.size()).append(")\n");
        for (Entry entry : this.collected) {
            text.append(String.format("  %-46s %s%n", entry.name(), entry.detail()));
        }

        text.append("\nSKIPPED (").append(this.skipped.size()).append(")\n");
        if (this.skipped.isEmpty()) {
            text.append("  (nothing)\n");
        } else {
            for (Entry entry : this.skipped) {
                text.append(String.format("  %-46s %s%n", entry.name(), entry.detail()));
            }
        }

        text.append("\nNOT INCLUDED, BY DESIGN\n");
        text.append("  server-types/*.jar and usual-plugins/*.jar are inventoried in\n");
        text.append("  artefacts.txt by name and size only -- they are hundreds of megabytes\n");
        text.append("  and reveal nothing a filename does not.\n");
        return text.toString();
    }
}
