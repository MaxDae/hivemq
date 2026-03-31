package com.hivemq.extensions.rbac.e2e;

import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.hivemq.HiveMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.Assert.*;

public class ExtensionIntegrationTestPlain {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionStartupIntegrationTest.class);

    @BeforeClass
    public static void printExtensionDir() {
        System.out.println("=== EXTENSION_DIR ===");
        System.out.println(TestContainerFactory.EXTENSION_DIR);
        System.out.println("=== EXISTS ON DISK ===");
        System.out.println(new java.io.File(TestContainerFactory.EXTENSION_DIR).exists());
    }

    @ClassRule
    public static HiveMQContainer container = createContainer();

    private static HiveMQContainer createContainer() {
        try {
            Path extensionDir = TestContainerFactory.createIsolatedExtensionDir();
            Path credentialsFile = Files.createTempFile("credentials-plain", ".xml");
            Files.writeString(credentialsFile,
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                            "<file-rbac>\n" +
                            "    <users>\n" +
                            "        <user>\n" +
                            "            <name>plainuser</name>\n" +
                            "            <password>plainpassword</password>\n" +
                            "            <roles><id>role1</id></roles>\n" +
                            "        </user>\n" +
                            "        <user>\n" +
                            "            <name>admin</name>\n" +
                            "            <password>adminpassword</password>\n" +
                            "            <roles><id>superuser</id></roles>\n" +
                            "        </user>\n" +
                            "    </users>\n" +
                            "    <roles>\n" +
                            "        <role>\n" +
                            "            <id>role1</id>\n" +
                            "            <permissions>\n" +
                            "                <permission><topic>data/${{clientid}}/#</topic></permission>\n" +
                            "            </permissions>\n" +
                            "        </role>\n" +
                            "        <role>\n" +
                            "            <id>superuser</id>\n" +
                            "            <permissions>\n" +
                            "                <permission><topic>#</topic></permission>\n" +
                            "            </permissions>\n" +
                            "        </role>\n" +
                            "    </roles>\n" +
                            "</file-rbac>");

            Path extensionConfigFile = Files.createTempFile("extension-config-plain", ".xml");
            Files.writeString(extensionConfigFile,
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                            "<extension-configuration>\n" +
                            "    <credentials-reload-interval>60</credentials-reload-interval>\n" +
                            "    <password-type>PLAIN</password-type>\n" +
                            "</extension-configuration>");
            Files.copy(credentialsFile, extensionDir.resolve("credentials.xml"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(extensionConfigFile, extensionDir.resolve("extension-config.xml"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            return new HiveMQContainer(
                    DockerImageName.parse(TestContainerFactory.HIVEMQ_IMAGE))
                    .withStartupTimeout(Duration.ofMinutes(5))
                    .withCreateContainerCmdModifier(cmd -> cmd.withUser("0"))
                    .withEnv("HIVEMQ_ALLOW_ALL_CLIENTS", "false")
                    .withExtension(MountableFile.forHostPath(extensionDir.toString()))
                    .withStartupTimeout(Duration.ofSeconds(120))
                    .withLogConsumer(new Slf4jLogConsumer(logger))
                    .waitingFor(Wait.forListeningPort());

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    // ─── Helper ───────────────────────────────────────────────────────────────

    private Mqtt5BlockingClient buildClient(String username, String password) {
        return Mqtt5Client.builder()
                .serverHost(container.getHost())
                .serverPort(container.getMqttPort())
                .simpleAuth()
                .username(username)
                .password(password.getBytes())
                .applySimpleAuth()
                .buildBlocking();
    }

    // ─── Test 1 — Extension started ──────────────────────────────────────────

    @Test
    public void whenPlainExtensionDeployed_shouldStartSuccessfully() {
        assertTrue(
                "Extension should start with PLAIN password type",
                container.getLogs().contains("Starting File RBAC extension.")
        );
    }

    // ─── Test 2 — Valid PLAIN credentials connect ─────────────────────────────

    @Test
    public void whenValidPlainCredentials_shouldConnect() {
        Mqtt5BlockingClient client = buildClient("plainuser", "plainpassword");

        try {
            client.connect();
            client.disconnect();
        } catch (Exception e) {
            fail("Valid PLAIN credentials should connect: " + e.getMessage());
        }
    }

    // ─── Test 3 — Wrong PLAIN password rejected ───────────────────────────────

    @Test
    public void whenWrongPlainPassword_shouldReject() {
        Mqtt5BlockingClient client = buildClient("plainuser", "wrongpassword");

        assertThrows(Exception.class, () -> client.connect());
    }

    // ─── Test 4 — No credentials rejected ────────────────────────────────────

    @Test
    public void whenNoCredentials_shouldReject() {
        Mqtt5BlockingClient client = Mqtt5Client.builder()
                .serverHost(container.getHost())
                .serverPort(container.getMqttPort())
                .buildBlocking();

        assertThrows(Exception.class, () -> client.connect());
    }

    // ─── Test 5 — '+' in username rejected with PLAIN ────────────────────────

    @Test
    public void whenAdminPlainCredentials_shouldConnect() {
        Mqtt5BlockingClient client = buildClient("admin", "adminpassword");

        try {
            client.connect();
            client.disconnect();
        } catch (Exception e) {
            fail("Admin with PLAIN password should connect: " + e.getMessage());
        }
    }
    @AfterClass
    public static void printLogs() {
        System.out.println("=== CONTAINER LOGS ===");
        System.out.println(container.getLogs());
        System.out.println("=== END LOGS ===");
    }
}