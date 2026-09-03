package dev.watchwolf.cli.parse;

import dev.watchwolf.cli.model.SessionInfo;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads {@code logs/<millis>/info.txt}.
 *
 * <p>The file is {@code key = value} lines. Two properties of how the ServersManager writes it
 * dictate this parser:
 *
 * <ul>
 *   <li><b>Order is not stable.</b> It iterates a {@code HashMap}, so the keys come out in hash
 *       order, not insertion order. Never read it positionally.</li>
 *   <li><b>Keys can repeat.</b> It writes with {@code StandardOpenOption.APPEND}, so re-running
 *       with the same id appends a second copy of every key. Last value wins here, which matches
 *       "the most recent run".</li>
 * </ul>
 */
public final class InfoTxtParser {
    private InfoTxtParser() {
    }

    public static SessionInfo parse(String sessionId, String contents) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (contents != null) {
            for (String line : contents.split("\\R")) {
                int separator = line.indexOf('=');
                if (separator < 0) continue;                    // blank or malformed; skip quietly
                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                if (key.isEmpty()) continue;
                fields.put(key, value);                         // last wins: APPEND duplicates keys
            }
        }
        return new SessionInfo(sessionId, fields);
    }
}
