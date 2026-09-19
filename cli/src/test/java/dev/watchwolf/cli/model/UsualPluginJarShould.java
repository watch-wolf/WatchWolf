package dev.watchwolf.cli.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class UsualPluginJarShould {
    @Test
    public void parseTheNamesAnActualInstallHolds() {
        UsualPluginJar worldGuard = UsualPluginJar.parse("WorldGuard-7.0.8-1.19-LATEST.jar");
        assertEquals("WorldGuard", worldGuard.name());
        assertEquals("7.0.8", worldGuard.pluginVersion());
        assertEquals(McVersion.of("1.19"), worldGuard.minMcVersion());
        assertEquals(McVersion.LATEST, worldGuard.maxMcVersion());

        UsualPluginJar worldEdit = UsualPluginJar.parse("WorldEdit-6.1.9-1.8-1.12.2.jar");
        assertEquals(McVersion.of("1.12.2"), worldEdit.maxMcVersion());
    }

    @Test
    public void recogniseTheWatchWolfServerJar() {
        assertTrue(UsualPluginJar.parse("WatchWolf-0.3-1.8-LATEST.jar").isWatchWolfServer());
        assertFalse(UsualPluginJar.parse("Vault-1.7.3-1.8-LATEST.jar").isWatchWolfServer());
    }

    @Test
    public void rejectNamesTheServersManagerCannotLoad() {
        // a name that does not match means the runtime silently ignores the plugin, so the
        // installer has to treat it as a hard failure rather than a cosmetic issue
        assertFalse(UsualPluginJar.isValidName("WorldGuard.jar"), "no version fields");
        assertFalse(UsualPluginJar.isValidName("WorldGuard-7.0.8-1.19-LATEST.zip"), "not a jar");
        assertFalse(UsualPluginJar.isValidName("WorldGuard-7.0.8-1.19.jar"), "no max version");
        // the plugin name is [^-]+, so a dash inside it swallows the version fields
        assertFalse(UsualPluginJar.isValidName("World-Guard-7.0.8-1.19-LATEST.jar"));
        assertThrows(IllegalArgumentException.class,
                () -> UsualPluginJar.parse("not-a-plugin.jar"));
    }

    @Test
    public void tolerateASpaceInThePluginNameBecauseTheServersManagerDoes() {
        // usual-plugins/README.md says spaces become '_', but the regex the ServersManager
        // actually applies is [^-]+, which permits them. Validating more strictly than the
        // runtime would reject a plugin that works.
        assertTrue(UsualPluginJar.isValidName("World Guard-7.0.8-1.19-LATEST.jar"));
        assertEquals("World Guard",
                UsualPluginJar.parse("World Guard-7.0.8-1.19-LATEST.jar").name());
    }

    @Test
    public void answerWhetherItCoversAServerVersion() {
        UsualPluginJar plugin = UsualPluginJar.parse("WorldGuard-7.0.0-1.13-1.13.2.jar");
        assertTrue(plugin.supports(McVersion.of("1.13")));
        assertTrue(plugin.supports(McVersion.of("1.13.2")));
        assertFalse(plugin.supports(McVersion.of("1.12.2")));
        assertFalse(plugin.supports(McVersion.of("1.14")));

        UsualPluginJar openEnded = UsualPluginJar.parse("WatchWolf-0.3-1.8-LATEST.jar");
        assertTrue(openEnded.supports(McVersion.of("1.21")));
    }

    @Test
    public void buildAValidNameFromItsParts() {
        String built = UsualPluginJar.fileNameFor(
                "My Plugin", "1.0", McVersion.of("1.8"), McVersion.LATEST);
        assertEquals("My_Plugin-1.0-1.8-LATEST.jar", built);
        assertTrue(UsualPluginJar.isValidName(built));
    }
}
