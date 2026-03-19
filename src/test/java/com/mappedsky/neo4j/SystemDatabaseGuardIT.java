package com.mappedsky.neo4j;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.exceptions.Neo4jException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the system-database guard agent.
 *
 * <p>Verifies that the Byte Buddy agent injected into Neo4j via {@code -javaagent:}
 * correctly enforces the {@code _admin} suffix policy for administrative commands
 * executed against the {@code system} database:
 *
 * <ul>
 *   <li>{@code _admin} users may run admin write commands (CREATE USER, etc.).</li>
 *   <li>Non-{@code _admin} users (including {@code _rw} users) are blocked.</li>
 *   <li>Read-only system commands (SHOW USERS) pass through for all users.</li>
 * </ul>
 *
 * <p>These tests use the shared {@link ContainerSetup} which starts a Neo4j
 * Testcontainer with both the plugin JAR and the agent JAR mounted, and with
 * {@code server.jvm.additional} configured to load the agent.
 */
class SystemDatabaseGuardIT {

    private static Driver adminSuffixDriver;
    private static Driver roDriver;
    private static Driver rwDriver;

    @BeforeAll
    static void setup() {
        ContainerSetup.ADMIN_DRIVER.verifyConnectivity();
        adminSuffixDriver = ContainerSetup.driverFor(ContainerSetup.ADMIN_SUFFIX_USER);
        roDriver          = ContainerSetup.driverFor(ContainerSetup.RO_USER);
        rwDriver          = ContainerSetup.driverFor(ContainerSetup.RW_USER);
    }

    @AfterAll
    static void teardown() {
        adminSuffixDriver.close();
        roDriver.close();
        rwDriver.close();
    }

    // ── _admin user: admin write commands succeed ─────────────────────────────

    @Test
    void adminSuffixUserCanCreateAndDropUser() {
        String newUser = "guard_test_" + System.nanoTime();
        try (Session s = adminSuffixDriver.session(SessionConfig.forDatabase("system"))) {
            assertThatCode(() ->
                    s.run("CREATE USER " + newUser
                            + " SET PASSWORD 'Passw0rd!' CHANGE NOT REQUIRED")
                     .consume())
                    .doesNotThrowAnyException();

            // Cleanup – also an admin write; should succeed for _admin user.
            assertThatCode(() -> s.run("DROP USER " + newUser).consume())
                    .doesNotThrowAnyException();
        }
    }

    // ── Non-_admin users: admin write commands are blocked ────────────────────

    @Test
    void roUserIsBlockedFromCreateUser() {
        try (Session s = roDriver.session(SessionConfig.forDatabase("system"))) {
            assertThatThrownBy(() ->
                    s.run("CREATE USER guard_ro_blocked SET PASSWORD 'Passw0rd!' CHANGE NOT REQUIRED")
                     .consume())
                    .isInstanceOf(Neo4jException.class)
                    .satisfies(e -> assertThat(((Neo4jException) e).code())
                            .containsIgnoringCase("Forbidden"));
        }
    }

    @Test
    void rwUserIsBlockedFromCreateUser() {
        // _rw suffix allows data-db writes but NOT system-db admin commands.
        try (Session s = rwDriver.session(SessionConfig.forDatabase("system"))) {
            assertThatThrownBy(() ->
                    s.run("CREATE USER guard_rw_blocked SET PASSWORD 'Passw0rd!' CHANGE NOT REQUIRED")
                     .consume())
                    .isInstanceOf(Neo4jException.class)
                    .satisfies(e -> assertThat(((Neo4jException) e).code())
                            .containsIgnoringCase("Forbidden"));
        }
    }

    // ── Read-only system commands pass through for non-_admin users ───────────

    @Test
    void roUserCanShowUsers() {
        // SHOW USERS is a read-only admin command routed through a separate
        // runtime in Neo4j 5.x and must not be blocked by the agent.
        try (Session s = roDriver.session(SessionConfig.forDatabase("system"))) {
            assertThatCode(() -> s.run("SHOW USERS").list())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void rwUserCanShowUsers() {
        try (Session s = rwDriver.session(SessionConfig.forDatabase("system"))) {
            assertThatCode(() -> s.run("SHOW USERS").list())
                    .doesNotThrowAnyException();
        }
    }
}
