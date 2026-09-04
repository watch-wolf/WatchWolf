package dev.watchwolf.cli.docker;

import dev.watchwolf.cli.model.JavaImageCatalog;
import dev.watchwolf.cli.progress.ProgressSink;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves every image {@code PullJdkImagesStep} pulls -- {@link JavaImageCatalog#allRequiredImages()}
 * -- is still a real, pullable tag on Docker Hub. This is what actually exercises the external
 * dependency; {@code PullJdkImagesStep} itself is a thin loop over {@link DockerFacade#pullImage}
 * already covered at the unit level against a fake, which cannot tell us whether
 * {@code eclipse-temurin:21-jdk} still exists.
 *
 * <p>Deliberately pulls all four, not one: a stale or renamed tag for just one Java version (e.g.
 * if temurin ever drops an old one) would otherwise go unnoticed.
 */
@Timeout(value = 10, unit = TimeUnit.MINUTES)
public class ITPullJdkImagesShould {
    private static DockerJavaFacade docker;

    @BeforeAll
    static void connect() {
        docker = DockerJavaFacade.connect();
    }

    @AfterAll
    static void disconnect() {
        if (docker != null) docker.close();
    }

    @Test
    void pullEveryRequiredJdkImage() {
        for (String image : JavaImageCatalog.allRequiredImages()) {
            if (!docker.imageExists(image)) {
                docker.pullImage(image, ProgressSink.discarding());
            }
            assertTrue(docker.imageExists(image), image + " did not exist after pulling it");
        }
    }
}
