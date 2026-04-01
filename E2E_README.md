# E2E and Test Guide

## Prerequisites

- Docker must be installed on the machine.
- Docker daemon must be running before starting E2E tests.

## How to run E2E tests

- Run all E2E tests:

```bash
mvn -B verify -Pe2e --file pom.xml
```

- Run E2E tests with a specific HiveMQ image:

```bash
mvn -B verify -Pe2e -Dhivemq.test.image=hivemq/hivemq-ce:latest --file pom.xml
```

- Run a single E2E test class:

```bash
mvn -B verify -Pe2e -Dit.test=InvalidPasswordTest --file pom.xml
```

## How to add a new E2E test

1. Create a new test class under:
   - `src/test/java/com/hivemq/extensions/rbac/e2e/`
2. Name it `*Test.java` or `*IntegrationTest.java` so Maven Failsafe includes it.
3. Reuse `TestContainerFactory.createIsolatedExtensionDir()` to avoid cross-test file contamination.
4. Start HiveMQ with `HiveMQContainer` and prefer `Wait.forListeningPort()` as startup condition.
5. Keep assertions behavior-based (connect/publish/subscribe outcome) and only use log checks as secondary evidence.

## When tests run in CI

- Unit tests run in GitHub Actions workflow `PR Unit Tests`.
- E2E tests run in manual workflow `E2E Tests (Manual)` with selectable HiveMQ image input.

## Jenkins

- See `Jenkinsfile.example` for a pipeline that runs both unit and E2E tests and publishes JUnit reports.
