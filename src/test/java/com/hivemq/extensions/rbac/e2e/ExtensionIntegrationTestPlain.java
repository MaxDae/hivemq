package com.hivemq.extensions.rbac.e2e;

import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
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

    @ClassRule
    public static HiveMQContainer container = createContainer();

    private static HiveMQContainer createContainer() {
        try {
            Path extensionDir = Path.of("target/unpacked-extension/hivemq-file-rbac-extension").toAbsolutePath();


            // Write config files directly into the extension dir — avoids volume override issues
            Files.writeString(extensionDir.resolve("credentials.xml"),
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

            Files.writeString(extensionDir.resolve("extension-config.xml"),
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                            "<extension-configuration>\n" +
                            "    <credentials-reload-interval>60</credentials-reload-interval>\n" +
                            "    <password-type>PLAIN</password-type>\n" +
                            "</extension-configuration>");

            return new HiveMQContainer(
                    DockerImageName.parse("hivemq/hivemq-ce:2025.5"))
                    .withStartupTimeout(Duration.ofMinutes(5))
                    .withCreateContainerCmdModifier(cmd -> cmd.withUser("0"))
                    .withEnv("HIVEMQ_ALLOW_ALL_CLIENTS", "false")

                    .withExtension(MountableFile.forHostPath(extensionDir))
                    .withStartupTimeout(Duration.ofSeconds(120))
                    .withLogConsumer(new Slf4jLogConsumer(logger))
                    .waitingFor(Wait.forLogMessage(".*File Role Based Access Control Extension.*started successfully.*", 1));

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
                container.getLogs().contains("started successfully")
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
}