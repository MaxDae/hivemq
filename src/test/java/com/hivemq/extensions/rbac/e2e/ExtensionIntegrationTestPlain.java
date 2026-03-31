package com.hivemq.extensions.rbac.e2e;

import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import org.junit.ClassRule;
import org.junit.Test;
import org.testcontainers.hivemq.HiveMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class ExtensionIntegrationTestPlain {

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
                            "    <password-type>PLAIN</password-type>\n" +
                            "</extension-configuration>"
            );

            return new HiveMQContainer(
                    DockerImageName.parse("hivemq/hivemq-ce:latest"))
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