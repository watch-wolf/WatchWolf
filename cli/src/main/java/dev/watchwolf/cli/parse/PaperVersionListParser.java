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
 * Extracts the available Paper versions from {@code fill.papermc.io/v3/projects/paper}.
 *
 * <p>Replaces {@code PaperBuilder.sh}'s {@code curl | jq '.versions | .[]' | grep -oP ...}. Paper
 * ships prebuilt jars, so unlike Spigot there is no build container -- these versions are simply
 * downloaded.
 *
 * <p>The old {@code api.papermc.io/v2} project endpoint (a flat {@code "versions": [...]} array)
 * stopped receiving builds at the end of 2025; PaperMC's replacement, the "Fill" service, groups
 * versions by family instead: {@code {"versions": {"1.21": ["1.21.11", "1.21.11-rc3", ..., "1.21"],
 * "26.1": ["26.1.2", "26.1.1"], ...}}}. Two things follow from that shape:
 *
 * <ul>
 *   <li>Only the array <b>values</b> are read, never the family key. A key like {@code "26.1"} is
 *       not necessarily itself a real, buildable version -- unlike the older {@code "1.x"} families,
 *       where the bare version (e.g. {@code "1.21"}) does also appear as one of its own array's
 *       entries.</li>
 *   <li>Each family's array mixes real releases with pre-releases/candidates ({@code "1.21.11-rc3"},
 *       {@code "1.13-pre7"}, {@code "26.2-rc-2"}). Requiring the whole quoted string to be digits
 *       and dots excludes every one of those without needing to know Paper's suffix vocabulary.</li>
 * </ul>
 *
 * <p>Parsed with regexes over the raw text rather than a full JSON bind, so a new unrelated field
 * in that document cannot break the installer.
 */
public final class PaperVersionListParser {
    /** One match per family, e.g. {@code "1.21":[...]} or {@code "26.1":[...]} -- family keys are
     *  themselves plain digits-and-dots, same as a real version, so this cannot mistake the wrong
     *  thing for a family boundary the way an unanchored version-shaped match could. */
    private static final Pattern FAMILY_ARRAY = Pattern.compile("\"[\\d.]+\"\\s*:\\s*\\[(.*?)]");
    /** Exactly digits and dots, quoted -- excludes any pre-release/candidate suffix. */
    private static final Pattern PURE_NUMERIC_VERSION = Pattern.compile("\"(\\d+(?:\\.\\d+)*)\"");

    private PaperVersionListParser() {
    }

    /** Newest first, de-duplicated, {@link McVersion#MIN_SUPPORTED} and above only. */
    public static List<McVersion> parse(String json) {
        if (json == null) return List.of();

        Set<String> seen = new LinkedHashSet<>();
        Matcher familyMatcher = FAMILY_ARRAY.matcher(json);
        while (familyMatcher.find()) {
            Matcher versionMatcher = PURE_NUMERIC_VERSION.matcher(familyMatcher.group(1));
            while (versionMatcher.find()) seen.add(versionMatcher.group(1));
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
