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
 */
public final class SpigotVersionListParser {
    private static final Pattern VERSION_FILE = Pattern.compile("(1\\.\\d+(?:\\.\\d+)?)\\.json");

    private SpigotVersionListParser() {
    }

    /** Newest first, de-duplicated. */
    public static List<McVersion> parse(String html) {
        Set<String> seen = new LinkedHashSet<>();
        if (html != null) {
            Matcher matcher = VERSION_FILE.matcher(html);
            while (matcher.find()) seen.add(matcher.group(1));
        }

        List<McVersion> versions = new ArrayList<>();
        for (String raw : seen) {
            McVersion parsed = McVersion.parseOrNull(raw);
            if (parsed != null) versions.add(parsed);
        }
        versions.sort(Comparator.reverseOrder());
        return versions;
    }
}
