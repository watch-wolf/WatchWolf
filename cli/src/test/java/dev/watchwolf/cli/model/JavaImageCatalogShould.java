package dev.watchwolf.cli.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

public class JavaImageCatalogShould {
    /**
     * These are the boundaries in WatchWolf-Core's DockerUtilities.getJavaVersion. Duplicated here
     * deliberately (Core is not a resolvable dependency); the code checks assert the two agree.
     */
    @ParameterizedTest
    @CsvSource({
            "1.8.8,   8",
            "1.12.2,  8",
            "1.16.5,  8",
            "1.17,   16",
            "1.17.1, 16",
            "1.18,   17",
            "1.20,   17",
            "1.20.4, 17",
            "1.20.5, 21",
            "1.21,   21",
    })
    public void mapEachMinecraftVersionToItsJavaVersion(String mcVersion, int expectedJava) {
        assertEquals(expectedJava, JavaImageCatalog.javaVersionFor(mcVersion));
    }

    @Test
    public void nameEclipseTemurinImagesNotOpenjdk() {
        // WatchWolfSetup.sh pulled openjdk:{8,16,17}; the ServersManager launches eclipse-temurin
        // and needs 21. Getting this wrong makes the pulls useless and 1.20.5+ fail lazily.
        assertEquals("eclipse-temurin:8-jdk", JavaImageCatalog.imageFor("1.8.8"));
        assertEquals("eclipse-temurin:21-jdk", JavaImageCatalog.imageFor("1.20.5"));
    }

    @Test
    public void requireAllFourJdkImages() {
        assertEquals(
                java.util.List.of("eclipse-temurin:8-jdk", "eclipse-temurin:16-jdk",
                                  "eclipse-temurin:17-jdk", "eclipse-temurin:21-jdk"),
                JavaImageCatalog.allRequiredImages());
    }

    @Test
    public void narrowTheImageListToTheVersionsActuallyInstalled() {
        assertEquals(
                java.util.Set.of("eclipse-temurin:8-jdk", "eclipse-temurin:17-jdk"),
                JavaImageCatalog.imagesRequiredBy(
                        java.util.List.of(McVersion.of("1.8.8"), McVersion.of("1.20.4"))));
    }
}
