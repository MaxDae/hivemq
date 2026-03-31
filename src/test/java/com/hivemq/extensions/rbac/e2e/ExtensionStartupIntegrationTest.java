package com.hivemq.extensions.rbac.e2e;

import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import org.junit.ClassRule;
import org.junit.Test;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.hivemq.HiveMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class ExtensionStartupIntegrationTest {

    @ClassRule
    public static HiveMQContainer container = createContainer();

    private static HiveMQContainer createContainer() {
        try {
            Path credentialsFile = Files.createTempFile("credentials", ".xml");
            Files.writeString(credentialsFile,
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                            "<file-rbac>\n" +
                            "    <users>\n" +
                            "        <user>\n" +
                            "            <name>user1</name>\n" +
                            "            <password>WFNQUVB0UkxjM04xa0hSR1BQNGhuOTJKVzdlbXA4bjk=:100:FY12nwpUEbBK9EKQ/Aw/rQKSoA7jXsC0HKELwU2mLCVU39bJVK0zf4NemuFeDOHPO4BW1nOjxi6NporkC6rUog==</password>\n" +
                            "            <roles><id>role1</id></roles>\n" +
                            "        </user>\n" +
                            "        <user>\n" +
                            "            <name>admin-user</name>\n" +
                            "            <password>Vjc1a0lxQ3Nvb0ljNFVHNE9WRnM3RG1IZmdNUFcwVGY=:100:PL2FLqfpdhONG7qXjAMmdVn4wlMiXnypdXiFW09zqorFhKgoiixFQw2EVJJfE9Zn79q45V7Xpc6JeKLp0ntmYA==</password>\n" +
                            "            <roles><id>superuser</id></roles>\n" +
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
                            "        <role>\n" +
                            "            <id>superuser</id>\n" +
                            "            <permissions>\n" +
                            "                <permission>\n" +
                            "                    <topic>#</topic>\n" +
                            "                </permission>\n" +
                            "            </permissions>\n" +
                            "        </role>\n" +
                            "    </roles>\n" +
                            "</file-rbac>"
            );

            Path configFile = Files.createTempFile("extension-config", ".xml");
            Files.writeString(configFile,
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                            "<extension-configuration>\n" +
                            "    <credentials-reload-interval>60</credentials-reload-interval>\n" +
                            "    <password-type>HASHED</password-type>\n" +
                            "</extension-configuration>"
            );

            return new HiveMQContainer(
                    DockerImageName.parse("hivemq/hivemq-ce:latest"))

                    // ← remove only allow-all, keep others
                    .withoutPrepackagedExtensions("hivemq-allow-all-extension")

                    .withExtension(MountableFile.forHostPath(
                            "target/unpacked-extension/hivemq-file-rbac-extension"))
                    .withFileInExtensionHomeFolder(
                            MountableFile.forHostPath(credentialsFile),
                            "hivemq-file-rbac-extension",
                            "credentials.xml")
                    .withFileInExtensionHomeFolder(
                            MountableFile.forHostPath(configFile),
                            "hivemq-file-rbac-extension",
                            "extension-config.xml");

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ─── Helper — builds MQTT client ─────────────────────────────────────────

    private Mqtt5BlockingClient buildClient(String username,
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


    @Test
    public void debug_logs() {
        String logs = container.getLogs();
        System.out.println(logs);

        // Look for:
        // WARN - No credentials configuration file
        // WARN - Configuration has errors
        // ERROR - Could not read
    }

    @Test
    public void whenExtensionDeployed_shouldStartSuccessfully() {
        String logs = container.getLogs();

        // Proves: build → deploy → start works
        assertTrue(
                "Extension should start successfully",
                logs.contains("File Role Based Access Control Extension") &&
                        logs.contains("started successfully")
        );
    }

    // ─── Test 2 — Valid credentials connect ───────────────────────────────────

    @Test
    public void whenValidCredentials_shouldConnect() {
        Mqtt5BlockingClient client = buildClient("user1", "pass1", "");

        try {
            client.connect();
            client.disconnect();
        } catch (Exception e) {
            fail("Valid credentials should connect: " + e.getMessage());
        }
    }

    @Test
    public void whenWrongPassword_shouldReject() {
        Mqtt5BlockingClient client = buildClient("user1", "wrongpassword", "");

        assertThrows(Exception.class, () -> client.connect());
    }

    @Test
    public void whenNoCredentials_shouldReject() {
        Mqtt5BlockingClient client = Mqtt5Client.builder()
                .serverHost(container.getHost())
                .serverPort(container.getMqttPort())
                .buildBlocking();

        assertThrows(Exception.class, () -> client.connect());
    }

    @Test
    public void whenAdminCredentials_shouldConnect() {
        Mqtt5BlockingClient client =
                buildClient("admin-user", "admin-password", "");

        try {
            client.connect();
            client.disconnect();
        } catch (Exception e) {
            fail("Admin should connect: " + e.getMessage());
        }
    }

    @Test
    public void whenPlusInUsername_shouldReject() {
        Mqtt5BlockingClient client =
                buildClient("user+wildcard", "pass1", "");

        assertThrows(
                "'+' in username should be rejected",
                Exception.class,
                () -> client.connect()
        );
    }

    @Test
    public void whenExtensionDeployed_credentialsShouldBeLoaded() {
        String logs = container.getLogs();

        // No warnings about missing credentials
        assertFalse(
                "Should not warn about missing credentials",
                logs.contains("No credentials configuration file") ||
                        logs.contains("denying all connections")
        );
    }

    @Test
    public void test_publish_to_allowed_topic()
            throws Exception {

        String clientId = "mydevice123";
        String topic = "data/" + clientId + "/temperature";
        String payload = "22.5";

        Mqtt5BlockingClient subscriber = buildClient(
                "user1", "pass1", clientId);
        subscriber.connect();

        Mqtt5BlockingClient.Mqtt5Publishes publishes =
                subscriber.publishes(MqttGlobalPublishFilter.SUBSCRIBED);

        subscriber.subscribeWith()
                .topicFilter(topic)
                .qos(MqttQos.AT_LEAST_ONCE)
                .send();

        // Step 2 — admin publishes to device topic
        Mqtt5BlockingClient publisher = buildClient(
                "admin-user", "admin-password", "admin-publisher");
        publisher.connect();

        publisher.publishWith()
                .topic(topic)
                .payload(payload.getBytes(StandardCharsets.UTF_8))
                .qos(MqttQos.AT_LEAST_ONCE)
                .send();

        // Step 3 — wait for message
        Optional<Mqtt5Publish> received =
                publishes.receive(5, TimeUnit.SECONDS);

        // Assert
        assertTrue("Message should be received on allowed topic",
                received.isPresent());
        assertEquals(
                "Payload should match",
                payload,
                new String(received.get().getPayloadAsBytes(),
                        StandardCharsets.UTF_8));

        publisher.disconnect();
        subscriber.disconnect();
    }

//    @Test
//    public void whenArchiveFolderIsFile_shouldDeleteAndArchiveSuccessfully()
//            throws Exception {
//
//        // Step 1 — find extension folder inside container
//        String extensionFolder =
//                "/opt/hivemq/extensions/hivemq-file-rbac-extension";
//        String archivePath = extensionFolder + "/credentials-archive";
//
//        // Step 2 — create a FILE where archive FOLDER should be
//        // This simulates the exact scenario the bug handles
//        container.execInContainer(
//                "sh", "-c",
//                "touch " + archivePath  // ← creates FILE not folder
//        );
//
//        // Verify it's a file not a folder
//        var result = container.execInContainer(
//                "sh", "-c",
//                "test -f " + archivePath + " && echo 'IS_FILE' || echo 'NOT_FILE'"
//        );
//        assertEquals("IS_FILE", result.getStdout().trim());
//
//        // Step 3 — trigger config reload by touching credentials.xml
//        container.execInContainer(
//                "sh", "-c",
//                "touch " + extensionFolder + "/credentials.xml"
//        );
//
//        // Step 4 — wait for reload interval
//        Thread.sleep(65_000); // ← wait for 60s reload interval + buffer
//
//        // Step 5 — check logs
//        String logs = container.getLogs();
//
//        // Before fix: IOException thrown on successful delete ❌
//        //             logs contain "Could not delete file"
//        //             archive never created
//        // After fix:  file deleted, folder created, archive written ✅
//        //             logs contain "Archived current credentials"
//        assertFalse(
//                "Should not throw IOException when deleting file — " +
//                        "before fix: throws on successful delete ❌",
//                logs.contains("Could not delete file")
//        );
//
//        assertTrue(
//                "Should archive successfully after deleting file — " +
//                        "after fix: archives correctly ✅",
//                logs.contains("Archived current credentials")
//        );
//
//        // Step 6 — verify archive folder was created correctly
//        var folderCheck = container.execInContainer(
//                "sh", "-c",
//                "test -d " + archivePath + " && echo 'IS_DIR' || echo 'NOT_DIR'"
//        );
//        assertEquals(
//                "Archive path should be a directory after fix",
//                "IS_DIR",
//                folderCheck.getStdout().trim()
//        );
//
//        // Step 7 — verify archive file was created inside folder
//        var archiveFileCheck = container.execInContainer(
//                "sh", "-c",
//                "ls " + archivePath + " | wc -l"
//        );
//        int archiveFileCount = Integer.parseInt(
//                archiveFileCheck.getStdout().trim());
//
//        assertTrue(
//                "At least one archive file should exist",
//                archiveFileCount > 0
//        );
//    }
}