package dev.watchwolf.cli.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The plan has to survive being handed to another process, because that is the only way an install
 * can carry on after the terminal it was started from goes away.
 */
public class BuildPlanFileShould {

    @Test
    public void surviveARoundTrip() {
        BuildPlan original = BuildPlan.builder()
                .branch("dev")
                .parallelBuilders(4)
                .cloneTester(false)
                .registerStartup(true)
                .buildPaper(false)
                .spigotVersions(List.of(McVersion.of("1.8.8"), McVersion.of("1.20.4")))
                .paperVersions(List.of(McVersion.of("1.21.1")))
                .selfTestSuites(Set.of("ITServerStarterShould"))
                .selectedUsualPlugins(Set.of("WorldEdit-7.2.9-1.13-1.20.jar"))
                .build();

        BuildPlan restored = BuildPlanFile.parse(BuildPlanFile.render(original));

        assertEquals("dev", restored.branch());
        assertEquals(4, restored.parallelBuilders());
        assertFalse(restored.cloneTester());
        assertTrue(restored.registerStartup());
        assertFalse(restored.buildPaper());
        assertEquals(original.spigotVersions(), restored.spigotVersions());
        assertEquals(original.paperVersions(), restored.paperVersions());
        assertEquals(Set.of("ITServerStarterShould"), restored.selfTestSuites());
        assertEquals(Set.of("WorldEdit-7.2.9-1.13-1.20.jar"), restored.selectedUsualPlugins());
    }

    @Test
    public void keepTheDifferenceBetweenEveryPluginAndNoPlugin() {
        // "unresolved" means "whatever watchwolf.dev lists", which is not the same as the empty
        // set -- writing one and reading the other would silently install the wrong thing
        BuildPlan unresolved = BuildPlan.defaults();
        assertFalse(unresolved.usualPluginsSelectionResolved());
        assertFalse(BuildPlanFile.parse(BuildPlanFile.render(unresolved))
                .usualPluginsSelectionResolved());

        BuildPlan none = BuildPlan.builder().selectedUsualPlugins(Set.of()).build();
        BuildPlan restored = BuildPlanFile.parse(BuildPlanFile.render(none));
        assertTrue(restored.usualPluginsSelectionResolved());
        assertTrue(restored.selectedUsualPlugins().isEmpty());
    }

    @Test
    public void keepTheDefaultsForAnythingTheFileDoesNotMention() {
        // an older file, written before a field existed: it must load, not explode
        BuildPlan restored = BuildPlanFile.parse("branch: dev\nparallel-builders: 2\n");

        assertEquals(2, restored.parallelBuilders());
        assertTrue(restored.cloneServersManager(), "an absent key keeps the BuildPlan default");
    }

    @Test
    public void ignoreCommentsBlankLinesAndNonsense() {
        BuildPlan restored = BuildPlanFile.parse(
                "# written by watchwolf\n\nnot a key value line\nparallel-builders: notanumber\n"
                        + "branch: dev\n");

        assertEquals("dev", restored.branch());
        assertEquals(1, restored.parallelBuilders(), "an unparseable number keeps the default");
    }
}
