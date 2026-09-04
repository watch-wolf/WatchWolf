package dev.watchwolf.cli.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * A {@link BuildPlan} as flat {@code key: value} text, so a run can be handed to another process.
 *
 * <p>Written when an install is sent to the background: the CLI runs attached to your terminal and
 * cannot detach itself, so the launcher starts a fresh detached container instead -- and that
 * container has to know exactly what was ticked, without reopening a menu nobody is watching.
 *
 * <p>Hand-rolled rather than reflected or YAML-mapped, for the reason the rest of the module parses
 * its inputs by hand: an unreadable or half-written file must degrade to "I could not read the
 * plan", never to a plan that silently lost a field. Anything unrecognised is ignored, and anything
 * absent keeps its {@link BuildPlan} default, so an older file still loads after a field is added.
 */
public final class BuildPlanFile {
    private static final String UNRESOLVED = "<unresolved>";

    private BuildPlanFile() {
    }

    public static String render(BuildPlan plan) {
        StringBuilder text = new StringBuilder();
        text.append("# Written by 'watchwolf build' when the install was sent to the background.\n");
        text.append("# It is replayed by the detached run; editing it changes what that run does.\n");
        line(text, "branch", plan.branch());
        line(text, "parallel-builders", String.valueOf(plan.parallelBuilders()));
        line(text, "clone-servers-manager", String.valueOf(plan.cloneServersManager()));
        line(text, "clone-clients-manager", String.valueOf(plan.cloneClientsManager()));
        line(text, "clone-tester", String.valueOf(plan.cloneTester()));
        line(text, "pull-jdk-images", String.valueOf(plan.pullJdkImages()));
        line(text, "download-watchwolf-server", String.valueOf(plan.downloadWatchWolfServer()));
        line(text, "build-servers-manager-image", String.valueOf(plan.buildServersManagerImage()));
        line(text, "build-clients-manager-image", String.valueOf(plan.buildClientsManagerImage()));
        line(text, "register-startup", String.valueOf(plan.registerStartup()));
        line(text, "register-launcher", String.valueOf(plan.registerLauncher()));
        line(text, "run-self-test", String.valueOf(plan.runSelfTest()));
        line(text, "build-spigot", String.valueOf(plan.buildSpigot()));
        line(text, "build-paper", String.valueOf(plan.buildPaper()));
        line(text, "spigot-versions", join(plan.spigotVersions()));
        line(text, "paper-versions", join(plan.paperVersions()));
        line(text, "self-test-suites", join(plan.selfTestSuites()));
        // the difference between "every plugin" and "these plugins" is not expressible as a list,
        // and getting it wrong would silently install the wrong set -- see BuildPlan#selectedUsualPlugins
        line(text, "usual-plugins", plan.usualPluginsSelectionResolved()
                ? join(plan.selectedUsualPlugins()) : UNRESOLVED);
        return text.toString();
    }

    public static BuildPlan parse(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String raw : text.split("\n")) {
            String stripped = raw.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) continue;
            int colon = stripped.indexOf(':');
            if (colon < 0) continue;
            values.put(stripped.substring(0, colon).strip(), stripped.substring(colon + 1).strip());
        }

        BuildPlan.Builder builder = BuildPlan.builder();
        if (values.containsKey("branch")) builder.branch(values.get("branch"));
        integer(values, "parallel-builders").ifPresent(builder::parallelBuilders);

        flag(values, "clone-servers-manager", builder::cloneServersManager);
        flag(values, "clone-clients-manager", builder::cloneClientsManager);
        flag(values, "clone-tester", builder::cloneTester);
        flag(values, "pull-jdk-images", builder::pullJdkImages);
        flag(values, "download-watchwolf-server", builder::downloadWatchWolfServer);
        flag(values, "build-servers-manager-image", builder::buildServersManagerImage);
        flag(values, "build-clients-manager-image", builder::buildClientsManagerImage);
        flag(values, "register-startup", builder::registerStartup);
        flag(values, "register-launcher", builder::registerLauncher);
        flag(values, "run-self-test", builder::runSelfTest);
        flag(values, "build-spigot", builder::buildSpigot);
        flag(values, "build-paper", builder::buildPaper);

        if (values.containsKey("spigot-versions")) {
            builder.spigotVersions(versions(values.get("spigot-versions")));
        }
        if (values.containsKey("paper-versions")) {
            builder.paperVersions(versions(values.get("paper-versions")));
        }
        if (values.containsKey("self-test-suites")) {
            builder.selfTestSuites(new LinkedHashSet<>(split(values.get("self-test-suites"))));
        }
        String plugins = values.get("usual-plugins");
        if (plugins != null && !plugins.equals(UNRESOLVED)) {
            builder.selectedUsualPlugins(new LinkedHashSet<>(split(plugins)));
        }
        return builder.build();
    }

    private static void line(StringBuilder text, String key, String value) {
        text.append(key).append(": ").append(value).append('\n');
    }

    private static String join(java.util.Collection<?> values) {
        List<String> text = new ArrayList<>();
        values.forEach(value -> text.add(String.valueOf(value)));
        return String.join(",", text);
    }

    private static List<String> split(String value) {
        List<String> parts = new ArrayList<>();
        for (String piece : value.split(",")) {
            String stripped = piece.strip();
            if (!stripped.isEmpty()) parts.add(stripped);
        }
        return parts;
    }

    private static List<McVersion> versions(String value) {
        List<McVersion> versions = new ArrayList<>();
        for (String piece : split(value)) {
            McVersion version = McVersion.parseOrNull(piece);
            if (version != null) versions.add(version);
        }
        return versions;
    }

    private static void flag(Map<String, String> values, String key,
                             java.util.function.Consumer<Boolean> setter) {
        String value = values.get(key);
        if (value != null) setter.accept(Boolean.parseBoolean(value));
    }

    private static java.util.OptionalInt integer(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) return java.util.OptionalInt.empty();
        try {
            return java.util.OptionalInt.of(Integer.parseInt(value));
        } catch (NumberFormatException ex) {
            return java.util.OptionalInt.empty();
        }
    }
}
