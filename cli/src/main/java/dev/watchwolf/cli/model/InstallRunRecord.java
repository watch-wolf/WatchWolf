package dev.watchwolf.cli.model;

import java.util.ArrayList;
import java.util.List;

/**
 * How the last install run ended, left behind for the next one to show.
 *
 * <p>Only a run that was <b>sent to the background</b> writes this. Nobody is watching that run's
 * output -- it happens in a detached container after the terminal was handed back -- so the result
 * has to wait somewhere until a person is looking again. The next {@code watchwolf build} opens
 * with it, behind an {@code < OK >} the user has to acknowledge, and then deletes it.
 *
 * <p>A foreground run deliberately writes nothing: its result was already on the screen, and
 * repeating it at the top of the next run would be noise.
 */
public record InstallRunRecord(String ending, String summary, String finishedAt,
                               List<String> failures) {

    public InstallRunRecord {
        failures = List.copyOf(failures);
    }

    public boolean succeeded() {
        return this.failures.isEmpty();
    }

    public String render() {
        StringBuilder text = new StringBuilder();
        text.append("ending: ").append(this.ending).append('\n');
        text.append("summary: ").append(this.summary).append('\n');
        text.append("finished-at: ").append(this.finishedAt).append('\n');
        for (String failure : this.failures) {
            // one line each: a multi-line failure would be indistinguishable from a new key
            text.append("failure: ").append(failure.replace('\n', ' ')).append('\n');
        }
        return text.toString();
    }

    public static InstallRunRecord parse(String text) {
        String ending = "";
        String summary = "";
        String finishedAt = "";
        List<String> failures = new ArrayList<>();

        for (String raw : text.split("\n")) {
            int colon = raw.indexOf(':');
            if (colon < 0) continue;
            String key = raw.substring(0, colon).strip();
            String value = raw.substring(colon + 1).strip();
            switch (key) {
                case "ending" -> ending = value;
                case "summary" -> summary = value;
                case "finished-at" -> finishedAt = value;
                case "failure" -> failures.add(value);
                default -> { }
            }
        }
        return new InstallRunRecord(ending, summary, finishedAt, failures);
    }
}
