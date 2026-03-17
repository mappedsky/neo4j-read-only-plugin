package com.mappedsky.neo4j;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.exceptions.Neo4jException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end policy tests for the read-only plugin.
 *
 * <p>Uses a containerized Neo4j instance ({@link ContainerSetup}) with the
 * built plugin JAR mounted in {@code /var/lib/neo4j/plugins/}.
 *
 * <p>Test coverage:
 * <ul>
 *   <li>Writes succeed for users whose name ends with {@code _rw}</li>
 *   <li>Writes are blocked for all other users</li>
 *   <li>Read-only queries always pass through regardless of username</li>
 *   <li>Username-suffix edge cases (mid-name {@code _rw}, exact {@code _rw})</li>
 *   <li>The built-in {@code neo4j} admin account is also blocked from writes</li>
 * </ul>
 */
class WriteEnforcementIT {

    private static Driver rwDriver;
    private static Driver roDriver;

    @BeforeAll
    static void setup() {
        ContainerSetup.ADMIN_DRIVER.verifyConnectivity();
        rwDriver = ContainerSetup.driverFor(ContainerSetup.RW_USER);
        roDriver = ContainerSetup.driverFor(ContainerSetup.RO_USER);
    }

    @AfterAll
    static void teardown() {
        rwDriver.close();
        roDriver.close();
    }

    @BeforeEach
    void seedBaseData() {
        // Ensure a base node exists for update/delete tests.
        withTx(rwDriver, tx -> tx.run("MERGE (n:Base {id:'seed'}) SET n.value = 0"));
    }

    // ── Writes allowed for _rw users ─────────────────────────────────────────

    @Test
    void rwUserCanCreateNode() {
        withTx(rwDriver, tx -> tx.run("CREATE (n:E2E {case:'createNode'})"));
    }

    @Test
    void rwUserCanCreateRelationship() {
        withTx(rwDriver, tx ->
                tx.run("MATCH (a:Base) CREATE (b:E2E {case:'rel'}) CREATE (a)-[:HAS]->(b)"));
    }

    @Test
    void rwUserCanSetProperty() {
        withTx(rwDriver, tx ->
                tx.run("MATCH (n:Base {id:'seed'}) SET n.value = 1"));
    }

    @Test
    void rwUserCanDetachDeleteNode() {
        withTx(rwDriver, tx -> tx.run("CREATE (n:Temp {toDelete:true})"));
        withTx(rwDriver, tx -> tx.run("MATCH (n:Temp {toDelete:true}) DETACH DELETE n"));
    }

    @Test
    void rwUserCanMerge() {
        withTx(rwDriver, tx ->
                tx.run("MERGE (n:Merge {key:'rwMerge'}) ON CREATE SET n.created = true"));
    }

    @Test
    void rwUserCanRemoveProperty() {
        withTx(rwDriver, tx -> tx.run("MATCH (n:Base {id:'seed'}) REMOVE n.value"));
    }

    // ── Writes blocked for non-_rw users ─────────────────────────────────────

    @Test
    void nonRwUserIsBlockedFromCreatingNode() {
        expectBlocked(roDriver, "CREATE (n:Blocked {x:1})");
    }

    @Test
    void nonRwUserIsBlockedFromCreatingRelationship() {
        expectBlocked(roDriver, "MATCH (a:Base),(b:Base) CREATE (a)-[:BLOCKED]->(b)");
    }

    @Test
    void nonRwUserIsBlockedFromSettingProperty() {
        expectBlocked(roDriver, "MATCH (n:Base {id:'seed'}) SET n.hacked = true");
    }

    @Test
    void nonRwUserIsBlockedFromDetachDelete() {
        expectBlocked(roDriver, "MATCH (n:Base) DETACH DELETE n");
    }

    @Test
    void nonRwUserIsBlockedFromMerge() {
        expectBlocked(roDriver, "MERGE (n:BlockedMerge {x:1})");
    }

    @Test
    void nonRwUserIsBlockedFromRemovingProperty() {
        expectBlocked(roDriver, "MATCH (n:Base {id:'seed'}) REMOVE n.value");
    }

    // ── Reads always pass through ─────────────────────────────────────────────

    @Test
    void nonRwUserCanMatchAllNodes() {
        try (Session s = roDriver.session(SessionConfig.forDatabase("neo4j"))) {
            long count = s.run("MATCH (n) RETURN count(n) AS c")
                          .single().get("c").asLong();
            assertThat(count).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void nonRwUserCanCallReadProcedure() {
        try (Session s = roDriver.session(SessionConfig.forDatabase("neo4j"))) {
            var labels = s.run("CALL db.labels() YIELD label RETURN label").list();
            assertThat(labels).isNotNull();
        }
    }

    @Test
    void nonRwUserCanUseWhereClause() {
        try (Session s = roDriver.session(SessionConfig.forDatabase("neo4j"))) {
            var result = s.run("MATCH (n:Base) WHERE n.id = 'seed' RETURN n.value AS v").list();
            assertThat(result).isNotEmpty();
        }
    }

    // ── Username-suffix edge cases ────────────────────────────────────────────

    @ParameterizedTest(name = "user ''{0}'' with _rw suffix can write")
    @ValueSource(strings = {ContainerSetup.RW_USER, ContainerSetup.SVC_RW_USER})
    void usersWithRwSuffixCanWrite(String username) {
        try (Driver d = ContainerSetup.driverFor(username)) {
            withTx(d, tx -> tx.run(
                    "CREATE (n:SuffixTest {user: $u})", Map.of("u", username)));
        }
    }

    @ParameterizedTest(name = "user ''{0}'' without _rw suffix is blocked")
    @ValueSource(strings = {ContainerSetup.RO_USER, ContainerSetup.SVC_RW_EXTRA})
    void usersWithoutRwSuffixAreBlocked(String username) {
        try (Driver d = ContainerSetup.driverFor(username)) {
            expectBlocked(d, "CREATE (n:SuffixTest {user: '" + username + "'})");
        }
    }

    @Test
    void neo4jBuiltInAdminIsBlockedFromWrites() {
        // The built-in 'neo4j' account has no _rw suffix and must be blocked.
        expectBlocked(ContainerSetup.ADMIN_DRIVER, "CREATE (n:AdminBlocked {src:'neo4j'})");
    }

    // ── Parameterized write-type coverage ────────────────────────────────────

    @Test
    void rwUserCanRunParameterizedWrite() {
        withTx(rwDriver, tx ->
                tx.run("CREATE (n:Param {x: $x, y: $y})", Map.of("x", 42, "y", "hello")));
    }

    @Test
    void nonRwUserIsBlockedEvenWithParameterizedWrite() {
        expectBlocked(roDriver, "CREATE (n:Param {x: $x})", Map.of("x", 1));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Runs {@code query} in an explicit transaction as {@code driver} and expects it to succeed. */
    private static void withTx(Driver driver, java.util.function.Consumer<Transaction> query) {
        try (Session s = driver.session(SessionConfig.forDatabase("neo4j"));
             Transaction tx = s.beginTransaction()) {
            query.accept(tx);
            assertThatCode(tx::commit).doesNotThrowAnyException();
        }
    }

    /** Asserts that the write {@code query} is blocked by the plugin for {@code driver}. */
    private static void expectBlocked(Driver driver, String query) {
        expectBlocked(driver, query, Map.of());
    }

    private static void expectBlocked(Driver driver, String query, Map<String, Object> params) {
        try (Session s = driver.session(SessionConfig.forDatabase("neo4j"));
             Transaction tx = s.beginTransaction()) {
            tx.run(query, params);
            assertThatThrownBy(tx::commit)
                    .isInstanceOf(Neo4jException.class)
                    .hasMessageContaining("not permitted");
        }
    }
}
