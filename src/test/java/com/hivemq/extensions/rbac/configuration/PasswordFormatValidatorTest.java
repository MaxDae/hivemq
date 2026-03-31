package com.hivemq.extensions.rbac.configuration;

import com.hivemq.extensions.rbac.configuration.entities.*;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class PasswordFormatValidatorTest {

    private ExtensionConfig extensionConfig;

    // ─── Helper — builds config with single user ──────────────────────────────

    private FileAuthConfig configWithPassword(String password) {
        Permission permission = new Permission("test/#");
        Role role = new Role("role1", List.of(permission));
        User user = new User("testuser", password, List.of("role1"));
        FileAuthConfig config = new FileAuthConfig();
        config.setUsers(List.of(user));
        config.setRoles(List.of(role));
        return config;
    }

    @Before
    public void setUp() {
        extensionConfig = new ExtensionConfig();
        extensionConfig.setPasswordType(PasswordType.HASHED);
    }

    // ─── Bug — split.length < 2 accepts 2-part password ──────────────────────

    @Test
    public void whenPasswordHasTwoParts_validatorShouldFail() {
        ConfigCredentialsValidator.ValidationResult result =
                ConfigCredentialsValidator.validateConfig(
                        extensionConfig,
                        configWithPassword("c29tZXNhbHQ=:100") // ← 2-part
                );

        // Before fix: split.length < 2 → 2 < 2 = false → passes ❌
        // After fix:  split.length != 3 → 2 != 3 = true → fails ✅
        assertFalse(
                "2-part password should fail validation — " +
                        "before fix: split.length < 2 accepts it ❌",
                result.isValidationSuccessful()
        );

        assertTrue(
                "Error should mention testuser",
                result.getErrors().stream()
                        .anyMatch(e -> e.contains("testuser"))
        );
    }

    // ─── 1-part password ──────────────────────────────────────────────────────

    @Test
    public void whenPasswordHasOnePart_validatorShouldFail() {
        ConfigCredentialsValidator.ValidationResult result =
                ConfigCredentialsValidator.validateConfig(
                        extensionConfig,
                        configWithPassword("c29tZXNhbHQ=") // ← 1-part
                );

        assertFalse(
                "1-part password should fail validation",
                result.isValidationSuccessful()
        );
    }

    // ─── 4-part password ──────────────────────────────────────────────────────

    @Test
    public void whenPasswordHasFourParts_validatorShouldFail() {
        ConfigCredentialsValidator.ValidationResult result =
                ConfigCredentialsValidator.validateConfig(
                        extensionConfig,
                        configWithPassword("salt:100:hash:extra") // ← 4-part
                );

        // Before fix: split.length < 2 → 4 < 2 = false → passes ❌
        // After fix:  split.length != 3 → 4 != 3 = true → fails ✅
        assertFalse(
                "4-part password should fail validation",
                result.isValidationSuccessful()
        );
    }

    // ─── Valid 3-part password ────────────────────────────────────────────────

    @Test
    public void test_password_three_parts() {
        ConfigCredentialsValidator.ValidationResult result =
                ConfigCredentialsValidator.validateConfig(
                        extensionConfig,
                        configWithPassword("c29tZXNhbHQ=:100:aGFzaA==") // ← 3-part
                );

        // Both before and after fix — passes ✅
        assertTrue(
                "3-part password should pass validation",
                result.isValidationSuccessful()
        );
        assertTrue(result.getErrors().isEmpty());
    }

    // ─── Non-numeric iterations ───────────────────────────────────────────────

    @Test
    public void test_iterations_should_be_numeric() {
        ConfigCredentialsValidator.ValidationResult result =
                ConfigCredentialsValidator.validateConfig(
                        extensionConfig,
                        configWithPassword("c29tZXNhbHQ=:abc:aGFzaA==") // ← abc
                );

        // Before fix: "abc" is not empty → passes ❌
        //             Integer.parseInt("abc") crashes later
        // After fix:  parseInt caught → fails with clear error ✅
        assertFalse(
                "Non-numeric iterations should fail validation",
                result.isValidationSuccessful()
        );

        assertTrue(
                "Error should mention non-numeric iterations",
                result.getErrors().stream()
                        .anyMatch(e -> e.contains("testuser"))
        );
    }

    // ─── Zero iterations ──────────────────────────────────────────────────────

    @Test
    public void test_iterations_zero_should_fail() {
        ConfigCredentialsValidator.ValidationResult result =
                ConfigCredentialsValidator.validateConfig(
                        extensionConfig,
                        configWithPassword("c29tZXNhbHQ=:0:aGFzaA==")
                );

        assertFalse(
                "Zero iterations should fail validation",
                result.isValidationSuccessful()
        );
    }

    @Test
    public void test_negative_iterations_should_fail() {
        ConfigCredentialsValidator.ValidationResult result =
                ConfigCredentialsValidator.validateConfig(
                        extensionConfig,
                        configWithPassword("c29tZXNhbHQ=:-1:aGFzaA==")
                );

        assertFalse(
                "Negative iterations should fail validation",
                result.isValidationSuccessful()
        );
    }

    // ─── Empty parts ──────────────────────────────────────────────────────────

    @Test
    public void test_salt_empty_should_fail() {
        ConfigCredentialsValidator.ValidationResult result =
                ConfigCredentialsValidator.validateConfig(
                        extensionConfig,
                        configWithPassword(":100:aGFzaA==")
                );

        assertFalse("Empty salt should fail", result.isValidationSuccessful());
    }

    @Test
    public void test_hash_empty_should_fail() {
        ConfigCredentialsValidator.ValidationResult result =
                ConfigCredentialsValidator.validateConfig(
                        extensionConfig,
                        configWithPassword("c29tZXNhbHQ=:100:")
                );

        assertFalse("Empty hash should fail", result.isValidationSuccessful());
    }

    // ─── PLAIN mode — no format check ────────────────────────────────────────

    @Test
    public void test_plain_password_not_check() {
        extensionConfig.setPasswordType(PasswordType.PLAIN);

        ConfigCredentialsValidator.ValidationResult result =
                ConfigCredentialsValidator.validateConfig(
                        extensionConfig,
                        configWithPassword("plainpassword")
                );

        assertTrue(
                "Plain password should pass — no format check in PLAIN mode",
                result.isValidationSuccessful()
        );
    }
}