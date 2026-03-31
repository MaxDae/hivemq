package com.hivemq.extensions.rbac.e2e;

import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import org.junit.Test;
import org.testcontainers.hivemq.HiveMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.Assert.*;

public class InvalidPasswordTest {

    // ─── Helper — builds extension-config.xml ────────────────────────────────

    private static Path buildExtensionConfig() throws Exception {
        Path configFile = Files.createTempFile("extension-config", ".xml");
        Files.writeString(configFile,
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                        "<extension-configuration>\n" +
                        "    <credentials-reload-interval>60</credentials-reload-interval>\n" +
                        "    <password-type>HASHED</password-type>\n" +
                        "</extension-configuration>"
        );
        return configFile;
    }

    // ─── Helper — builds HiveMQ container with given credentials.xml ─────────

    private static HiveMQContainer buildContainer(Path credentialsFile)
            throws Exception {
        return new HiveMQContainer(
                DockerImageName.parse("hivemq/hivemq-ce:2025.5"))
                .withStartupTimeout(Duration.ofMinutes(5))
                .withExtension(MountableFile.forHostPath(
                        "target/unpacked-extension/hivemq-file-rbac-extension"))
                .withoutPrepackagedExtensions()
                .withFileInExtensionHomeFolder(
                        MountableFile.forHostPath(credentialsFile),
                        "hivemq-file-rbac-extension",
                        "credentials.xml")
                .withFileInExtensionHomeFolder(
                        MountableFile.forHostPath(buildExtensionConfig()),
                        "hivemq-file-rbac-extension",
                        "extension-config.xml");
    }

    // ─── Helper — builds credentials.xml ─────────────────────────────────────

    private static Path buildCredentials(String goodPassword,
                                         String badPassword) throws Exception {
        Path credentialsFile = Files.createTempFile("credentials", ".xml");
        Files.writeString(credentialsFile,
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                        "<file-rbac>\n" +
                        "    <users>\n" +
                        "        <user>\n" +
                        "            <name>gooduser</name>\n" +
                        "            <password>" + goodPassword + "</password>\n" +
                        "            <roles><id>role1</id></roles>\n" +
                        "        </user>\n" +
                        "        <user>\n" +
                        "            <name>baduser</name>\n" +
                        "            <password>" + badPassword + "</password>\n" +
                        "            <roles><id>role1</id></roles>\n" +
                        "        </user>\n" +
                        "    </users>\n" +
                        "    <roles>\n" +
                        "        <role>\n" +
                        "            <id>role1</id>\n" +
                        "            <permissions>\n" +
                        "                <permission>\n" +
                        "                    <topic>test/#</topic>\n" +
                        "                </permission>\n" +
                        "            </permissions>\n" +
                        "        </role>\n" +
                        "    </roles>\n" +
                        "</file-rbac>"
        );
        return credentialsFile;
    }

    // ─── Valid hash for "pass1" ───────────────────────────────────────────────

    private static final String VALID_HASH =
            "WFNQUVB0UkxjM04xa0hSR1BQNGhuOTJKVzdlbXA4bjk=:100:" +
                    "FY12nwpUEbBK9EKQ/Aw/rQKSoA7jXsC0HKELwU2mLCVU39bJVK0zf4" +
                    "NemuFeDOHPO4BW1nOjxi6NporkC6rUog==";

    // ─── Test 1 — 2-part password logged as error ─────────────────────────────

    @Test
    public void whenPasswordHasTwoParts_validatorShouldLogError()
            throws Exception {

        // baduser has 2-part password — missing hash
        Path credentialsFile = buildCredentials(
                VALID_HASH,          // gooduser — valid
                "c29tZXNhbHQ=:100"  // baduser  — 2-part ← missing hash
        );

        try (HiveMQContainer container = buildContainer(credentialsFile)) {
            container.start();

            // Before fix: validator accepts 2-part → no error logged ❌
            // After fix:  validator rejects 2-part → error logged    ✅
            String logs = container.getLogs();

            assertTrue(
                    "Validator should log error about invalid password format — " +
                            "before fix: silent, no error ❌ " +
                            "after fix: clear error in logs ✅",
                    logs.contains("invalid password") ||
                            logs.contains("has errors")
            );
        }
    }


    // ─── Test 2 — baduser cannot connect ─────────────────────────────────────
    @Test
    public void whenPasswordHasTwoParts_baduserShouldNotConnect()
            throws Exception {

        Path credentialsFile = buildCredentials(
                VALID_HASH,          // gooduser — valid
                "c29tZXNhbHQ=:100"  // baduser  — 2-part
        );

        try (HiveMQContainer container = buildContainer(credentialsFile)) {
            container.start();

            Mqtt5BlockingClient badClient = Mqtt5Client.builder()
                    .serverHost(container.getHost())
                    .serverPort(container.getMqttPort())
                    .simpleAuth()
                    .username("baduser")
                    .password("anypassword".getBytes())
                    .applySimpleAuth()
                    .buildBlocking();

            // Before fix: validator accepts → loads → silent auth failure
            // After fix:  validator rejects → never loads → auth failure
            // Both cases: connection rejected ✅
            // Difference: logs contain error only after fix
            assertThrows(
                    "baduser with 2-part password should never authenticate",
                    Exception.class,
                    () -> badClient.connect()
            );
        }
    }

    // ─── Test 4 — non-numeric iterations ─────────────────────────────────────

    @Test
    public void whenIterationsNotNumeric_validatorShouldLogError()
            throws Exception {

        Path credentialsFile = buildCredentials(
                VALID_HASH,                      // gooduser — valid
                "c29tZXNhbHQ=:abc:aGFzaA=="     // baduser  — non-numeric iterations
        );

        try (HiveMQContainer container = buildContainer(credentialsFile)) {
            container.start();

            // Before fix: validator accepts → Integer.parseInt("abc") crashes ❌
            // After fix:  validator rejects → clear error logged ✅
            String logs = container.getLogs();

            assertTrue(
                    "Validator should log error about non-numeric iterations — " +
                            "before fix: Integer.parseInt crashes silently ❌ " +
                            "after fix: clear validation error ✅",
                    logs.contains("invalid password") ||
                            logs.contains("non-numeric") ||
                            logs.contains("has errors")
            );
        }
    }
}