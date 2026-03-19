package com.mappedsky.neo4j;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.exceptions.Neo4jException;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Paths;

/**
 * Shared Neo4j connection for all *IT test classes.
 *
 * <p>Supports two modes, selected at static-initializer time:
 *
 * <ol>
 *   <li><strong>External mode</strong> – when the environment variable
 *       {@code NEO4J_BOLT_URL} is set (e.g. from {@code docker-compose.test.yml}).
 *       No container is started; tests connect to the already-running instance.</li>
 *   <li><strong>Testcontainers mode</strong> – when {@code NEO4J_BOLT_URL} is
 *       absent.  A {@link Neo4jContainer} is started using the plugin JAR
 *       specified by the system property {@code plugin.jar.path} (set by
 *       {@code maven-failsafe-plugin} to the shaded artifact path).</li>
 * </ol>
 */
public final class ContainerSetup {

    // ── Fixed credentials ────────────────────────────────────────────────────

    static final String ADMIN_PASSWORD = "changeme";
    static final String USER_PASSWORD  = "testpass";

    // ── Test users ────────────────────────────────────────────────────────────

    /** Allowed to write – username ends with {@code _rw}. */
    static final String RW_USER      = "alice_rw";
    /** Blocked from writing – no {@code _rw} suffix. */
    static final String RO_USER      = "alice";
    /** Allowed – shortest valid {@code _rw} username. */
    static final String SVC_RW_USER  = "svc_rw";
    /** Blocked – {@code _rw} appears mid-name, not as suffix. */
    static final String SVC_RW_EXTRA = "svc_rw_extra";
    /** Allowed to run admin commands on system DB – username ends with {@code _admin}. */
    static final String ADMIN_SUFFIX_USER = "alice_admin";

    // ── Shared state ─────────────────────────────────────────────────────────

    /**
     * The Bolt URL the tests connect to.  Always non-null after class init.
     */
    static final String BOLT_URL;

    /** Admin driver (authenticated as {@code neo4j}/{@link #ADMIN_PASSWORD}). */
    static final Driver ADMIN_DRIVER;

    /**
     * Non-null only in Testcontainers mode; {@code null} in external mode.
     * Tests must not call {@code isRunning()} on this directly.
     */
    static final Neo4jContainer<?> CONTAINER;

    // ── Static initializer ───────────────────────────────────────────────────

    static {
        String externalBoltUrl = System.getenv("NEO4J_BOLT_URL");

        final String resolvedBoltUrl;
        final Neo4jContainer<?> resolvedContainer;

        if (externalBoltUrl != null) {
            // ── External mode (docker-compose.test.yml) ──────────────────────
            resolvedBoltUrl  = externalBoltUrl;
            resolvedContainer = null;
        } else {
            // ── Testcontainers mode (local dev / CI with Docker socket) ───────
            String pluginJar = System.getProperty(
                    "plugin.jar.path",
                    "target/neo4j-read-only-plugin-1.0.0-SNAPSHOT-plugin.jar");
            String agentJar = System.getProperty(
                    "agent.jar.path",
                    "target/neo4j-read-only-plugin-1.0.0-SNAPSHOT-agent.jar");

            Neo4jContainer<?> c = new Neo4jContainer<>("neo4j:5.20.0")
                    .withAdminPassword(ADMIN_PASSWORD)
                    .withCopyToContainer(
                            MountableFile.forHostPath(Paths.get(pluginJar), 0644),
                            "/var/lib/neo4j/plugins/neo4j-read-only-plugin.jar")
                    .withCopyToContainer(
                            MountableFile.forHostPath(Paths.get(agentJar), 0644),
                            "/var/lib/neo4j/plugins/system-guard-agent.jar")
                    .withEnv("NEO4J_dbms_security_procedures_unrestricted", "*")
                    .withEnv("NEO4J_server_jvm_additional",
                             "-javaagent:/var/lib/neo4j/plugins/system-guard-agent.jar");
            c.start();
            resolvedContainer = c;
            resolvedBoltUrl   = c.getBoltUrl();
        }

        BOLT_URL  = resolvedBoltUrl;
        CONTAINER = resolvedContainer;
        ADMIN_DRIVER = GraphDatabase.driver(BOLT_URL, AuthTokens.basic("neo4j", ADMIN_PASSWORD));

        if (CONTAINER != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                ADMIN_DRIVER.close();
                CONTAINER.stop();
            }));
        }

        // Create test users – idempotent (ignore "already exists" errors).
        // Note: executed as the built-in 'neo4j' admin, which is exempt from the
        // system-guard agent's _admin suffix check to allow initial bootstrap.
        try (Session sys = ADMIN_DRIVER.session(SessionConfig.forDatabase("system"))) {
            for (String user : new String[]{
                    RW_USER, RO_USER, SVC_RW_USER, SVC_RW_EXTRA, ADMIN_SUFFIX_USER}) {
                try {
                    sys.run("CREATE USER " + user
                            + " SET PASSWORD '" + USER_PASSWORD + "'"
                            + " CHANGE NOT REQUIRED").consume();
                } catch (Neo4jException ignored) {
                    // User already exists from a previous run – fine.
                }
            }
        }
    }

    /** Returns a new {@link Driver} authenticated as {@code username}/{@link #USER_PASSWORD}. */
    static Driver driverFor(String username) {
        return GraphDatabase.driver(BOLT_URL, AuthTokens.basic(username, USER_PASSWORD));
    }

    private ContainerSetup() {}
}
