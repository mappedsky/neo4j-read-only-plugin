package com.mappedsky.neo4j;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.TransactionFailureException;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests that spin up an embedded Neo4j instance with the plugin
 * loaded via the service-loader mechanism (the harness picks up the
 * META-INF/services file on the classpath automatically).
 *
 * <p>Because the harness does not support impersonation / user switching at
 * the embedded level, we register the listener directly and invoke
 * {@code beforeCommit} with mock {@link TransactionData} to validate the
 * policy.  A separate test validates the extension wiring.
 */
class ReadOnlyPluginIT {

    private Neo4j embeddedNeo4j;
    private GraphDatabaseService db;

    @BeforeEach
    void setUp() {
        embeddedNeo4j = Neo4jBuilders.newInProcessBuilder()
                .withDisabledServer()
                .build();
        db = embeddedNeo4j.defaultDatabaseService();
    }

    @AfterEach
    void tearDown() {
        embeddedNeo4j.close();
    }

    // -----------------------------------------------------------------------
    // Unit-style tests against the listener directly (no user-switching needed)
    // -----------------------------------------------------------------------

    @Test
    void rwUserCanWrite() throws Exception {
        var listener = listenerWithLog();
        var data = MockTransactionData.withWrites("alice_rw");

        // Must not throw
        listener.beforeCommit(data, null, db);
    }

    @Test
    void nonRwUserIsBlocked() {
        var listener = listenerWithLog();
        var data = MockTransactionData.withWrites("alice");

        assertThatThrownBy(() -> listener.beforeCommit(data, null, db))
                .isInstanceOf(WriteNotAllowedException.class)
                .hasMessageContaining("alice")
                .hasMessageContaining("_rw");
    }

    @Test
    void nonRwUserCanReadWithoutException() throws Exception {
        var listener = listenerWithLog();
        var data = MockTransactionData.readOnly("alice");

        // Read-only transaction: must not throw regardless of username
        listener.beforeCommit(data, null, db);
    }

    @Test
    void usernameWithRwInMiddleIsBlocked() {
        var listener = listenerWithLog();
        // "_rw" must be a suffix, not just anywhere in the name
        var data = MockTransactionData.withWrites("alice_rw_extra");

        assertThatThrownBy(() -> listener.beforeCommit(data, null, db))
                .isInstanceOf(WriteNotAllowedException.class);
    }

    @Test
    void exactRwSuffixIsAllowed() throws Exception {
        var listener = listenerWithLog();
        var data = MockTransactionData.withWrites("svc_rw");

        listener.beforeCommit(data, null, db);
    }

    // -----------------------------------------------------------------------
    // Smoke test: the extension wires up and Neo4j starts without error
    // -----------------------------------------------------------------------

    @Test
    void embeddedInstanceStartsSuccessfully() {
        // If the extension factory throws during startup the Neo4j harness
        // constructor above would already have failed.
        try (Transaction tx = db.beginTx()) {
            assertThat(tx).isNotNull();
            tx.commit();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ReadOnlyTransactionEventListener listenerWithLog() {
        // Use a no-op Log for unit tests to avoid needing a LogService
        return new ReadOnlyTransactionEventListener(NoOpLog.INSTANCE);
    }
}
