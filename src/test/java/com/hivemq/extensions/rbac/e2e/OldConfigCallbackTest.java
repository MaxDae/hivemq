package com.hivemq.extensions.rbac;

import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import org.junit.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.hivemq.HiveMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class OldConfigCallbackTest {

    private static final String EXTENSION_FOLDER =
            "/opt/hivemq/extensions/hivemq-file-rbac-extension";

    // ─── Helper — builds valid hashed credentials ─────────────────────────────

    private static Path buildCredentials(String username) throws Exception {
        Path file = Files.createTempFile("credentials", ".xml");
        Files.writeString(file,
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                        "<file-rbac>\n" +
                        "    <users>\n" +
                        "        <user>\n" +
                        "            <name>" + username + "</name>\n" +
                        "            <password>WFNQUVB0UkxjM04xa0hSR1BQNGhuOTJKVzdlbXA4bjk=:100:FY12nwpUEbBK9EKQ/Aw/rQKSoA7jXsC0HKELwU2mLCVU39bJVK0zf4NemuFeDOHPO4BW1nOjxi6NporkC6rUog==</password>\n" +
                        "            <roles><id>role1</id></roles>\n" +
                        "        </user>\n" +
                        "    </users>\n" +
                        "    <roles>\n" +
                        "        <role>\n" +
                        "            <id>role1</id>\n" +
                        "            <permissions>\n" +
                        "                <permission>\n" +
                        "                    <topic>data/${{clientid}}/#</topic>\n" +
                        "                </permission>\n" +
                        "            </permissions>\n" +
                        "        </role>\n" +
                        "    </roles>\n" +
                        "</file-rbac>"
        );
        return file;
    }

    private static Path buildConfig() throws Exception {
        Path file = Files.createTempFile("extension-config", ".xml");
        Files.writeString(file,
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                        "<extension-configuration>\n" +
                        "    <credentials-reload-interval>5</credentials-reload-interval>\n" +
                        "    <password-type>HASHED</password-type>\n" +
                        "</extension-configuration>"
        );
        return file;
    }

    private HiveMQContainer buildContainer(Path credentialsFile)
            throws Exception {
        return new HiveMQContainer(
                DockerImageName.parse("hivemq/hivemq-ce:latest"))
                .withExtension(MountableFile.forHostPath(
                        "target/unpacked-extension/hivemq-file-rbac-extension"))
                .withFileInExtensionHomeFolder(
                        MountableFile.forHostPath(credentialsFile),
                        "hivemq-file-rbac-extension",
                        "credentials.xml")
                .withFileInExtensionHomeFolder(
                        MountableFile.forHostPath(buildConfig()),
                        "hivemq-file-rbac-extension",
                        "extension-config.xml");
    }

    private String exec(HiveMQContainer container, String command)
            throws Exception {
        Container.ExecResult result = container.execInContainer("sh", "-c",
                command);
        return result.getStdout().trim();
    }

    private Mqtt5BlockingClient buildClient(HiveMQContainer container,
                                            String username,
                                            String password,
                                            String clientId) {
        return Mqtt5Client.builder()
                .serverHost(container.getHost())
                .serverPort(container.getMqttPort())
                .identifier(clientId)
                .simpleAuth()
                .username(username)
                .password(password.getBytes())
                .applySimpleAuth()
                .buildBlocking();
    }

    // ─── Test 1 — new user connects after reload ──────────────────────────────

    @Test
    public void whenCredentialsReload_newUserShouldConnect() throws Exception {

        // Start with user1
        try (HiveMQContainer container =
                     buildContainer(buildCredentials("user1"))) {

            container.start();

            // Verify user1 connects
            Mqtt5BlockingClient user1 = buildClient(
                    container, "user1", "pass1", "device1");
            try {
                user1.connect();
                user1.disconnect();
            } catch (Exception e) {
                fail("user1 should connect initially: " + e.getMessage());
            }

            // Write new credentials with user2
            Path newCredentials = buildCredentials("user2");
            container.copyFileToContainer(
                    MountableFile.forHostPath(newCredentials),
                    EXTENSION_FOLDER + "/credentials.xml");

            // Wait for reload
            Thread.sleep(10_000);

            // Verify logs show reload happened
            assertTrue("Reload should be logged",
                    container.getLogs().contains(
                            "Credentials configuration for file auth extension changed"));

            // user2 should now connect — proves new config loaded
            Mqtt5BlockingClient user2 = buildClient(
                    container, "user2", "pass1", "device2");
            try {
                user2.connect();
                user2.disconnect();
            } catch (Exception e) {
                fail("user2 should connect after reload: " + e.getMessage());
            }
        }
    }

    // ─── Test 2 — old user rejected after reload ──────────────────────────────

    @Test
    public void whenCredentialsReload_oldUserShouldBeRejected()
            throws Exception {

        // Start with user1
        try (HiveMQContainer container =
                     buildContainer(buildCredentials("user1"))) {

            container.start();

            // Verify user1 connects initially
            Mqtt5BlockingClient user1Before = buildClient(
                    container, "user1", "pass1", "device1");
            try {
                user1Before.connect();
                user1Before.disconnect();
            } catch (Exception e) {
                fail("user1 should connect initially: " + e.getMessage());
            }

            // Replace with user2 only — user1 removed
            Path newCredentials = buildCredentials("user2");
            container.copyFileToContainer(
                    MountableFile.forHostPath(newCredentials),
                    EXTENSION_FOLDER + "/credentials.xml");

            // Wait for reload
            Thread.sleep(10_000);

            // user1 should now be rejected — proves old config replaced
            Mqtt5BlockingClient user1After = buildClient(
                    container, "user1", "pass1", "device1-after");

            // Before fix: if oldConfig == newConfig callbacks might not
            //             update maps correctly
            // After fix:  maps updated with new config → user1 rejected ✅
            assertThrows(
                    "user1 should be rejected after reload removes them",
                    Exception.class,
                    () -> user1After.connect()
            );
        }
    }

    // ─── Test 3 — reload logged exactly once ──────────────────────────────────

    @Test
    public void whenConfigChangesOnce_reloadShouldBeLoggedOnce()
            throws Exception {

        try (HiveMQContainer container =
                     buildContainer(buildCredentials("user1"))) {

            container.start();

            // Clear initial logs by noting current log length
            int initialLogCount = container.getLogs()
                    .split("Credentials configuration for file auth " +
                            "extension changed", -1).length - 1;

            // Trigger exactly one reload
            Path newCredentials = buildCredentials("user2");
            container.copyFileToContainer(
                    MountableFile.forHostPath(newCredentials),
                    EXTENSION_FOLDER + "/credentials.xml");

            Thread.sleep(10_000);

            // Count reload log lines
            int totalLogCount = container.getLogs()
                    .split("Credentials configuration for file auth " +
                            "extension changed", -1).length - 1;

            int newReloadCount = totalLogCount - initialLogCount;

            // Before fix: might log multiple times ❌
            // After fix:  logs exactly once ✅
            assertEquals(
                    "Config change should be logged exactly once per reload",
                    1,
                    newReloadCount
            );
        }
    }

    // ─── Test 4 — the core bug: observable via user map update ───────────────

    @Test
    public void whenConfigReloads_userMapShouldReflectNewConfig()
            throws Exception {

        // This test proves the bug's IMPACT:
        // If oldConfig == newConfig in callbacks:
        //   updateMaps(newConfig) still works correctly
        //   because it uses newConfig not oldConfig
        // So the map update itself is not broken
        // The bug only affects callbacks that COMPARE old vs new

        // What we CAN test: that after reload the new user is in the map
        // This proves the callback received and applied newConfig correctly

        try (HiveMQContainer container =
                     buildContainer(buildCredentials("user1"))) {

            container.start();

            // Step 1 — user1 connects ✅
            Mqtt5BlockingClient user1 = buildClient(
                    container, "user1", "pass1", "d1");
            try {
                user1.connect();
                user1.disconnect();
            } catch (Exception e) {
                fail("user1 should connect: " + e.getMessage());
            }

            // Step 2 — update credentials to user2
            container.copyFileToContainer(
                    MountableFile.forHostPath(buildCredentials("user2")),
                    EXTENSION_FOLDER + "/credentials.xml");
            Thread.sleep(10_000);

            // Step 3 — user2 connects ✅ proves newConfig applied
            Mqtt5BlockingClient user2 = buildClient(
                    container, "user2", "pass1", "d2");
            try {
                user2.connect();
                user2.disconnect();
            } catch (Exception e) {
                fail("user2 should connect after reload — " +
                        "newConfig not applied to map: " + e.getMessage());
            }

            // Step 4 — user1 rejected ✅ proves old user removed
            Mqtt5BlockingClient user1After = buildClient(
                    container, "user1", "pass1", "d1-after");
            assertThrows(
                    "user1 should be rejected — old config should be replaced",
                    Exception.class,
                    () -> user1After.connect()
            );
        }
    }
}
