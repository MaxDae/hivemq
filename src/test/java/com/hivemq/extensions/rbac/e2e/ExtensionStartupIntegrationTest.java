package com.hivemq.extensions.rbac.e2e;

import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import org.junit.AfterClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.hivemq.HiveMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class ExtensionStartupIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionStartupIntegrationTest.class);


    @ClassRule
    public static HiveMQContainer container = createContainer();

    private static HiveMQContainer createContainer() {
        try {
            TestContainerFactory.assertExtensionBundlePresent();
            final Path extensionDir = TestContainerFactory.createIsolatedExtensionDir();
            final Path credentialsFile = Files.createTempFile("credentials-startup-plain", ".xml");
            Files.writeString(credentialsFile,
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                            "<file-rbac>\n" +
                            "    <users>\n" +
                            "        <user>\n" +
                            "            <name>user1</name>\n" +
                            "            <password>pass1</password>\n" +
                            "            <roles><id>role1</id></roles>\n" +
                            "        </user>\n" +
                            "        <user>\n" +
                            "            <name>admin-user</name>\n" +
                            "            <password>admin-password</password>\n" +
                            "            <roles><id>superuser</id></roles>\n" +
                            "        </user>\n" +
                            "    </users>\n" +
                            "    <roles>\n" +
                            "        <role>\n" +
                            "            <id>role1</id>\n" +
                            "            <permissions>\n" +
                            "                <permission><topic>data/${{clientid}}/#</topic></permission>\n" +
                            "                <permission><topic>incoming/${{username}}/actions</topic><activity>SUBSCRIBE</activity></permission>\n" +
                            "            </permissions>\n" +
                            "        </role>\n" +
                            "        <role>\n" +
                            "            <id>superuser</id>\n" +
                            "            <permissions><permission><topic>#</topic></permission></permissions>\n" +
                            "        </role>\n" +
                            "    </roles>\n" +
                            "</file-rbac>");
            final Path extensionConfigFile = Files.createTempFile("extension-config-startup-plain", ".xml");
            Files.writeString(extensionConfigFile,
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                            "<extension-configuration>\n" +
                            "    <credentials-reload-interval>60</credentials-reload-interval>\n" +
                            "    <password-type>PLAIN</password-type>\n" +
                            "</extension-configuration>");
            Files.copy(credentialsFile, extensionDir.resolve("credentials.xml"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(extensionConfigFile, extensionDir.resolve("extension-config.xml"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return new HiveMQContainer(DockerImageName.parse(TestContainerFactory.HIVEMQ_IMAGE))
                    .withStartupTimeout(Duration.ofMinutes(5))
                    .withCreateContainerCmdModifier(cmd -> cmd.withUser("0"))
                    .withEnv("HIVEMQ_ALLOW_ALL_CLIENTS", "false")
                    .withExtension(MountableFile.forHostPath(extensionDir.toString()))
                    .withLogConsumer(new Slf4jLogConsumer(logger))
                    .waitingFor(Wait.forListeningPort());
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
                logs.contains("Starting File RBAC extension.")
        );
    }

    // ─── Test 2 — Valid credentials connect ───────────────────────────────────

    @Test
    public void whenValidCredentials_shouldConnect() {
        Mqtt5BlockingClient client = buildClient("user1", "pass1", "valid-client");

        try {
            client.connect();
            client.disconnect();
        } catch (Exception e) {
            fail("Valid credentials should connect: " + e.getMessage());
        }
    }

    @Test
    public void whenWrongPassword_shouldReject() {
        Mqtt5BlockingClient client = buildClient("user1", "wrongpassword", "wrong-password-client");

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
                buildClient("admin-user", "admin-password", "admin-client");

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
                buildClient("user+wildcard", "pass1", "plus-user-client");

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

    @AfterClass
    public static void printLogs() {
        System.out.println("=== CONTAINER LOGS ===");
        System.out.println(container.getLogs());
        System.out.println("=== END LOGS ===");
    }
}