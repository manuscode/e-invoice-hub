package io.github.manuscode.invoicehub.validation;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

/**
 * KoSIT only resolves XML schemas from {@code file:} URLs, so the configuration can't be read from inside the
 * application jar. It is copied to a temporary directory on startup instead.
 */
@Component
class XRechnungConfiguration {

    private static final String LOCATION = "kosit/xrechnung/";

    private final Path directory;

    XRechnungConfiguration() {
        this.directory = copyToTemporaryDirectory();
    }

    Path scenarios() {
        return directory.resolve("scenarios.xml");
    }

    @PreDestroy
    void deleteTemporaryDirectory() throws IOException {
        FileSystemUtils.deleteRecursively(directory);
    }

    private static Path copyToTemporaryDirectory() {
        try {
            Path directory = Files.createTempDirectory("xrechnung-configuration");
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath:" + LOCATION + "**");
            if (resources.length == 0) {
                throw new IllegalStateException(LOCATION + " not found on classpath, it is downloaded by the Maven build");
            }
            Arrays.stream(resources)
                    .filter(XRechnungConfiguration::isFile)
                    .forEach(resource -> copy(resource, directory));
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not provide XRechnung configuration", e);
        }
    }

    private static boolean isFile(Resource resource) {
        try {
            return resource.isReadable() && !resource.getURL().toString().endsWith("/");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void copy(Resource resource, Path directory) {
        try (InputStream content = resource.getInputStream()) {
            String url = resource.getURL().toString();
            Path target = directory.resolve(url.substring(url.lastIndexOf(LOCATION) + LOCATION.length()));
            Files.createDirectories(target.getParent());
            Files.copy(content, target);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not copy " + resource, e);
        }
    }
}
