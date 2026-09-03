package dev.watchwolf.cli.parse;

import dev.watchwolf.cli.model.McVersion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the buildable Spigot versions from the {@code hub.spigotmc.org/versions/} index page.
 *
 * <p>Replaces {@code SpigotBuilder.sh}'s
 * {@code curl ... | grep -oP '1\.\d+(\.\d+)?(?=\.json)' | sort -rV | uniq -d}.
 *
 * <p><b>The {@code uniq -d} is dropped on purpose.</b> That flag prints only lines that appear more
 * than once, so any version listed a single time on the index was silently discarded and could
 * never be installed -- with no message saying so. Here every version found is returned, and
 * de-duplication keeps the first occurrence.
 *
 * <p><b>The match must anchor to {@code href="..."}, not float free in the document.</b> An earlier
 * version of this pattern (unanchored, and requiring a leading {@code "1."}) both missed every
 * version in Spigot's newer, non-{@code 1.x} numbering (e.g. {@code 26.1}, {@code 26.2}) and,
 * because {@code find()} matches anywhere, silently invented phantom versions: scanning
 * {@code 26.1.1.json} it would match the embedded substring {@code "1.1.json"} and report a
 * version {@code 1.1} that was never actually listed (same for {@code 26.1.2.json} -&gt;
 * {@code 1.2}). Anchoring to {@code href="..."} makes the match consume the whole filename via
 * backtracking instead of a lucky inner substring, which fixes both bugs at once.
 *
 * <p>The index also lists thousands of BuildTools' own per-build metadata files
 * ({@code 2600.json}, {@code 4617.json}, ...), which are bare integers with no dot; requiring at
 * least one {@code (?:\.\d+)+} group excludes all of them without needing to know their range.
 */
public final class SpigotVersionListParser {
    private static final Pattern VERSION_FILE =
            Pattern.compile("href=\"(\\d+(?:\\.\\d+)+)\\.json\"");

    private SpigotVersionListParser() {
    }

    /** Newest first, de-duplicated, {@link McVersion#MIN_SUPPORTED} and above only. */
    public static List<McVersion> parse(String html) {
        Set<String> seen = new LinkedHashSet<>();
        if (html != null) {
            Matcher matcher = VERSION_FILE.matcher(html);
            while (matcher.find()) seen.add(matcher.group(1));
        }

        List<McVersion> versions = new ArrayList<>();
        for (String raw : seen) {
            McVersion parsed = McVersion.parseOrNull(raw);
            if (parsed != null && parsed.isAtLeast(McVersion.MIN_SUPPORTED)) versions.add(parsed);
        }
        versions.sort(Comparator.reverseOrder());
        return versions;
    }
}
