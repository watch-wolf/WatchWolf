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
 * Extracts the available Paper versions from {@code api.papermc.io/v2/projects/paper}.
 *
 * <p>Replaces {@code PaperBuilder.sh}'s {@code curl | jq '.versions | .[]' | grep -oP ...}. Paper
 * ships prebuilt jars, so unlike Spigot there is no build container -- these versions are simply
 * downloaded.
 *
 * <p>Parsed with a regex over the {@code "versions"} array rather than a full JSON bind, so a new
 * unrelated field in that document cannot break the installer.
 */
public final class PaperVersionListParser {
    private static final Pattern VERSIONS_ARRAY =
            Pattern.compile("\"versions\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern QUOTED_VERSION = Pattern.compile("\"(1\\.\\d+(?:\\.\\d+)?)\"");

    private PaperVersionListParser() {
    }

    /** Newest first, de-duplicated. */
    public static List<McVersion> parse(String json) {
        if (json == null) return List.of();

        Matcher arrayMatcher = VERSIONS_ARRAY.matcher(json);
        String scope = arrayMatcher.find() ? arrayMatcher.group(1) : json;

        Set<String> seen = new LinkedHashSet<>();
        Matcher versionMatcher = QUOTED_VERSION.matcher(scope);
        while (versionMatcher.find()) seen.add(versionMatcher.group(1));

        List<McVersion> versions = new ArrayList<>();
        for (String raw : seen) {
            McVersion parsed = McVersion.parseOrNull(raw);
            if (parsed != null) versions.add(parsed);
        }
        versions.sort(Comparator.reverseOrder());
        return versions;
    }
}
