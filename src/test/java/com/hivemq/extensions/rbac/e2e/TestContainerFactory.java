package com.hivemq.extensions.rbac.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.IOException;

public final class TestContainerFactory {

    public static final String HIVEMQ_IMAGE =
            System.getProperty("hivemq.test.image", "hivemq/hivemq-ce:latest");

    public static final String EXTENSION_DIR =
            Path.of("target/unpacked-extension/hivemq-file-rbac-extension")
                    .toAbsolutePath()
                    .toString();

    public static void assertExtensionBundlePresent() {
        final Path extensionDir = Path.of(EXTENSION_DIR);
        if (!Files.isDirectory(extensionDir)) {
            throw new IllegalStateException("Missing unpacked extension directory: " + extensionDir);
        }
        final String[] requiredFiles = new String[]{
                "hivemq-extension.xml",
                "extension-config.xml",
                "credentials.xml"
        };
        for (String requiredFile : requiredFiles) {
            if (!Files.exists(extensionDir.resolve(requiredFile))) {
                throw new IllegalStateException(
                        "Unpacked extension is missing required file '" + requiredFile + "' in " + extensionDir);
            }
        }
    }

    public static Path createIsolatedExtensionDir() throws IOException {
        assertExtensionBundlePresent();
        final Path sourceDir = Path.of(EXTENSION_DIR);
        final Path tempRoot = Files.createTempDirectory("hivemq-ext-it-");
        final Path targetDir = tempRoot.resolve("hivemq-file-rbac-extension");

        Files.walk(sourceDir).forEach(sourcePath -> {
            try {
                final Path relativePath = sourceDir.relativize(sourcePath);
                final Path targetPath = targetDir.resolve(relativePath);
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        return targetDir;
    }

    private TestContainerFactory() {}
}