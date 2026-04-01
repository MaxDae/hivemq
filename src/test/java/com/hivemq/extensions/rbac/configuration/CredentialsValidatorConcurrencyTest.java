package com.hivemq.extensions.rbac.configuration;

import com.hivemq.extensions.rbac.configuration.Configuration;
import com.hivemq.extensions.rbac.configuration.entities.*;
import com.codahale.metrics.MetricRegistry;
import com.hivemq.extensions.rbac.utils.CredentialsValidator;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CredentialsValidatorConcurrencyTest {

    private Configuration mockConfiguration;
    private ExtensionConfig extensionConfig;
    private CredentialsValidator validator;

    // Captured reload callback — so we can trigger it manually
    private Configuration.ReloadCallback reloadCallback;

    @Before
    public void setUp() {
        mockConfiguration = mock(Configuration.class);
        extensionConfig = new ExtensionConfig();
        extensionConfig.setPasswordType(PasswordType.PLAIN);

        // Capture the reload callback when addReloadCallback() is called
        doAnswer(invocation -> {
            reloadCallback = invocation.getArgument(0);
            return null;
        }).when(mockConfiguration).addReloadCallback(any());

        // Initial config — user1 with role1
        when(mockConfiguration.getCurrentConfig())
                .thenReturn(buildConfig("user1", "pass1", "role1"));

        validator = new CredentialsValidator(
                mockConfiguration,
                extensionConfig,
                new MetricRegistry()
        );
        validator.init();
    }

    // ─── Helper — builds config with single user and role ─────────────────────

    private FileAuthConfig buildConfig(String username,
                                       String password,
                                       String roleId) {
        Permission permission = new Permission("test/#");

        Role role = new Role(roleId, List.of(permission));

        User user = new User(username, password, List.of(roleId));

        FileAuthConfig config = new FileAuthConfig();
        config.setUsers(List.of(user));
        config.setRoles(List.of(role));
        return config;
    }

    private ByteBuffer toBuffer(String password) {
        return ByteBuffer.wrap(password.getBytes(StandardCharsets.UTF_8));
    }

    // ─── Core bug test — non-atomic map update ────────────────────────────────

    @Test
    public void test_reload_happens_connecting_client_shouldnt_get_npe()
            throws InterruptedException {

        // New config — new user with new role
        // This is the dangerous case:
        // user2 references role2 which only exists in new config
        FileAuthConfig newConfig = buildConfig("user2", "pass2", "role2");

        int iterations = 10000; // run many times to catch the race
        AtomicInteger npeCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicReference<Throwable> caughtException = new AtomicReference<>();

        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        for (int i = 0; i < iterations; i++) {

            CountDownLatch iterationDone = new CountDownLatch(2);

            // Thread 1 — simulates config reload
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // Trigger reload — this calls updateUsersMap then updateRolesMap
                    reloadCallback.onReload(null, newConfig);
                } catch (Exception e) {
                    caughtException.set(e);
                } finally {
                    iterationDone.countDown();
                }
            });

            // Thread 2 — simulates client connecting during reload
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // Try to authenticate as new user
                    List<String> roles = validator.getRoles(
                            "user2",
                            toBuffer("pass2")
                    );

                    if (roles != null && !roles.isEmpty()) {
                        // Try to get permissions — this is where NPE happens
                        try {
                            List<?> permissions = validator.getPermissions(
                                    "client1",
                                    "user2",
                                    roles
                            );
                            successCount.incrementAndGet();
                        } catch (NullPointerException e) {
                            // 💥 This is the bug — role found in users map
                            // but not yet in roles map
                            npeCount.incrementAndGet();
                            caughtException.set(e);
                        }
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    caughtException.set(e);
                } finally {
                    iterationDone.countDown();
                }
            });

            // Release both threads simultaneously — maximizes race condition chance
            startLatch.countDown();
            iterationDone.await(5, TimeUnit.SECONDS);

            // Reset for next iteration
            // Reset config back to initial state
            reloadCallback.onReload(null,
                    buildConfig("user1", "pass1", "role1"));
        }

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        System.out.println("Results after " + iterations + " iterations:");
        System.out.println("  NPE count:     " + npeCount.get());
        System.out.println("  Success count: " + successCount.get());
        System.out.println("  Fail count:    " + failCount.get());

        if (caughtException.get() != null) {
            System.out.println("  Exception: " + caughtException.get());
        }

        // Before fix: npeCount > 0 — NPE caught ❌
        // After fix:  npeCount = 0 — no NPE      ✅
        assertEquals(
                "NullPointerException should never occur during config reload. " +
                        "Got " + npeCount.get() + " NPEs in " + iterations + " iterations.",
                0,
                npeCount.get()
        );
    }

    @Test
    public void test_getPermissions_during_reload_should_not_throw() throws Exception {
        final FileAuthConfig configA = buildConfig("user1", "pass1", "role1");
        final FileAuthConfig configB = buildConfig("user2", "pass2", "role2");

        final int iterations = 5000;
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicInteger npeCount = new AtomicInteger(0);
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final CountDownLatch startLatch = new CountDownLatch(1);

        executor.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < iterations; i++) {
                    reloadCallback.onReload(null, (i % 2 == 0) ? configA : configB);
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < iterations; i++) {
                    try {
                        validator.getPermissions("client-" + i, "user-" + i, List.of("role1", "role2"));
                    } catch (NullPointerException npe) {
                        npeCount.incrementAndGet();
                        failure.compareAndSet(null, npe);
                    } catch (RuntimeException runtimeException) {
                        // Known test-environment limitation: HiveMQ Builders requires extension classloader.
                        final String message = runtimeException.getMessage();
                        if (message == null || !message.contains("Static class Builders cannot be called")) {
                            failure.compareAndSet(null, runtimeException);
                        }
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        startLatch.countDown();
        executor.shutdown();
        assertTrue("Executor did not terminate in time", executor.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals("No NullPointerException expected while reading permissions during reload", 0, npeCount.get());

        if (failure.get() != null) {
            fail("No exception expected while reading permissions during reload, but got: " + failure.get());
        }
    }
}