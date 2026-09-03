package dev.watchwolf.cli.model;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which JDK image a Minecraft version runs on, and therefore which images an install must pull.
 *
 * <p><b>This deliberately duplicates {@code dev.watchwolf.core.utils.DockerUtilities.getJavaVersion}.</b>
 * WatchWolf-Core is not published to any resolvable repository -- the ServersManager installs it
 * from a local {@code lib/} jar during its clean phase -- so a Java 17 CLI cannot depend on it
 * without inheriting that whole mechanism. The duplication is instead policed by
 * {@code MinecraftJavaVersionsMatchesCoreShould} in the code checks, which reads Core's source when
 * the sibling checkout is present and fails the build on drift.
 *
 * <p>This is also the fix for a real drift already in the tree: {@code WatchWolfSetup.sh} pre-pulls
 * {@code openjdk:{8,16,17}}, but {@code DockerizedServerInstantiator} launches
 * {@code eclipse-temurin:<v>-jdk} and needs <b>21</b> for MC 1.20.5+. Deriving both the pull list
 * and the doctor check from here means there is one place to be wrong.
 */
public final class JavaImageCatalog {
    private static final McVersion V_1_17 = McVersion.of("1.17");
    private static final McVersion V_1_18 = McVersion.of("1.18");
    private static final McVersion V_1_20_5 = McVersion.of("1.20.5");

    /** Every Java version a server can need, so every image an install must have locally. */
    public static final List<Integer> ALL_JAVA_VERSIONS = List.of(8, 16, 17, 21);

    private JavaImageCatalog() {
    }

    /** Mirrors {@code DockerUtilities.getJavaVersion}. */
    public static int javaVersionFor(McVersion mcVersion) {
        if (mcVersion.isBelow(V_1_17)) return 8;         // prior to 1.17
        if (mcVersion.isBelow(V_1_18)) return 16;        // 1.17.x
        if (mcVersion.isBelow(V_1_20_5)) return 17;      // 1.18 .. 1.20.4
        return 21;                                       // 1.20.5 and later
    }

    public static int javaVersionFor(String mcVersion) {
        return javaVersionFor(McVersion.of(mcVersion));
    }

    /** Mirrors {@code DockerizedServerInstantiator.getDockerImageForJavaVersion}. */
    public static String imageForJavaVersion(int javaVersion) {
        return "eclipse-temurin:" + javaVersion + "-jdk";
    }

    public static String imageFor(McVersion mcVersion) {
        return imageForJavaVersion(javaVersionFor(mcVersion));
    }

    public static String imageFor(String mcVersion) {
        return imageFor(McVersion.of(mcVersion));
    }

    /** The full pre-pull list. */
    public static List<String> allRequiredImages() {
        return ALL_JAVA_VERSIONS.stream().map(JavaImageCatalog::imageForJavaVersion).toList();
    }

    /** Only the images the versions actually installed need -- what the coverage check uses. */
    public static Set<String> imagesRequiredBy(Collection<McVersion> installedVersions) {
        Set<String> images = new LinkedHashSet<>();
        for (McVersion version : installedVersions) images.add(imageFor(version));
        return images;
    }
}
