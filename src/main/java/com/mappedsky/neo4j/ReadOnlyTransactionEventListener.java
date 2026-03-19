package com.mappedsky.neo4j;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.event.PropertyEntry;
import org.neo4j.graphdb.event.TransactionData;
import org.neo4j.graphdb.event.TransactionEventListener;
import org.neo4j.logging.Log;

/**
 * Enforces the following policy for every committing transaction:
 * <ol>
 *   <li>If the transaction contains any write operations the changes are
 *       logged (counts per change type plus the acting username).</li>
 *   <li>If the acting user's name does <em>not</em> match the pattern
 *       {@code <anything>_rw} the commit is rejected by throwing
 *       {@link WriteNotAllowedException}, which causes Neo4j to roll back
 *       the transaction.</li>
 * </ol>
 *
 * <p>Pure read-only transactions pass through without any additional work.
 */
public class ReadOnlyTransactionEventListener implements TransactionEventListener<Void> {

    private static final String RW_SUFFIX    = "_rw";
    private static final String ADMIN_SUFFIX = "_admin";

    private final Log log;

    public ReadOnlyTransactionEventListener(Log log) {
        this.log = log;
    }

    // -------------------------------------------------------------------------
    // TransactionEventListener contract
    // -------------------------------------------------------------------------

    /**
     * Called before every commit.  If the transaction is a write and the
     * acting user is not allowed to write, this method throws
     * {@link WriteNotAllowedException} to roll back the transaction.
     */
    @Override
    public Void beforeCommit(
            TransactionData data,
            Transaction transaction,
            GraphDatabaseService databaseService) throws WriteNotAllowedException {

        if (!hasWrites(data)) {
            return null; // read-only – nothing to check
        }

        String username = data.username();
        logChanges(data, username);

        if (!username.endsWith(RW_SUFFIX) && !username.endsWith(ADMIN_SUFFIX)) {
            throw new WriteNotAllowedException(
                    "User '" + username + "' is not permitted to perform write operations. "
                    + "Write access requires a username ending in '" + RW_SUFFIX + "' or '"
                    + ADMIN_SUFFIX + "'.");
        }

        return null;
    }

    /** Called after a successful commit – nothing to do here. */
    @Override
    public void afterCommit(TransactionData data, Void state, GraphDatabaseService databaseService) {
        // intentionally empty
    }

    /** Called after a rollback – nothing to do here. */
    @Override
    public void afterRollback(TransactionData data, Void state, GraphDatabaseService databaseService) {
        // intentionally empty
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if {@code data} contains at least one write
     * operation (node/relationship creation or deletion, or any property
     * assignment/removal).
     */
    static boolean hasWrites(TransactionData data) {
        return data.createdNodes().iterator().hasNext()
                || data.deletedNodes().iterator().hasNext()
                || data.createdRelationships().iterator().hasNext()
                || data.deletedRelationships().iterator().hasNext()
                || data.assignedNodeProperties().iterator().hasNext()
                || data.removedNodeProperties().iterator().hasNext()
                || data.assignedRelationshipProperties().iterator().hasNext()
                || data.removedRelationshipProperties().iterator().hasNext();
    }

    /**
     * Logs a summary of every write category present in {@code data}.
     * This is called for <em>all</em> write transactions, including those that
     * will subsequently be blocked (the log message is written before the
     * rollback is triggered).
     */
    private void logChanges(TransactionData data, String username) {
        int createdNodes      = count(data.createdNodes());
        int deletedNodes      = count(data.deletedNodes());
        int createdRels       = count(data.createdRelationships());
        int deletedRels       = count(data.deletedRelationships());
        int assignedNodeProps = count(data.assignedNodeProperties());
        int removedNodeProps  = count(data.removedNodeProperties());
        int assignedRelProps  = count(data.assignedRelationshipProperties());
        int removedRelProps   = count(data.removedRelationshipProperties());

        log.info(
                "Write transaction by user '%s': "
                + "createdNodes=%d, deletedNodes=%d, "
                + "createdRelationships=%d, deletedRelationships=%d, "
                + "assignedNodeProperties=%d, removedNodeProperties=%d, "
                + "assignedRelationshipProperties=%d, removedRelationshipProperties=%d",
                username,
                createdNodes, deletedNodes,
                createdRels, deletedRels,
                assignedNodeProps, removedNodeProps,
                assignedRelProps, removedRelProps);

        // Detailed per-entry logging (DEBUG level to avoid flooding the log
        // in high-throughput deployments).
        if (log.isDebugEnabled()) {
            logNodeDetails(data);
            logRelationshipDetails(data);
            logPropertyDetails(data);
        }
    }

    private void logNodeDetails(TransactionData data) {
        for (Node node : data.createdNodes()) {
            log.debug("  + Node[%d] labels=%s", node.getId(), node.getLabels());
        }
        for (Node node : data.deletedNodes()) {
            log.debug("  - Node[%d]", node.getId());
        }
    }

    private void logRelationshipDetails(TransactionData data) {
        for (Relationship rel : data.createdRelationships()) {
            log.debug("  + Relationship[%d] :%s (%d)->(%d)",
                    rel.getId(), rel.getType().name(),
                    rel.getStartNodeId(), rel.getEndNodeId());
        }
        for (Relationship rel : data.deletedRelationships()) {
            log.debug("  - Relationship[%d] :%s (%d)->(%d)",
                    rel.getId(), rel.getType().name(),
                    rel.getStartNodeId(), rel.getEndNodeId());
        }
    }

    private void logPropertyDetails(TransactionData data) {
        for (PropertyEntry<Node> entry : data.assignedNodeProperties()) {
            log.debug("  Node[%d].%s = %s (was: %s)",
                    entry.entity().getId(), entry.key(),
                    entry.value(), entry.previouslyCommittedValue());
        }
        for (PropertyEntry<Node> entry : data.removedNodeProperties()) {
            log.debug("  Node[%d].%s removed (was: %s)",
                    entry.entity().getId(), entry.key(),
                    entry.previouslyCommittedValue());
        }
        for (PropertyEntry<Relationship> entry : data.assignedRelationshipProperties()) {
            log.debug("  Relationship[%d].%s = %s (was: %s)",
                    entry.entity().getId(), entry.key(),
                    entry.value(), entry.previouslyCommittedValue());
        }
        for (PropertyEntry<Relationship> entry : data.removedRelationshipProperties()) {
            log.debug("  Relationship[%d].%s removed (was: %s)",
                    entry.entity().getId(), entry.key(),
                    entry.previouslyCommittedValue());
        }
    }

    private static <T> int count(Iterable<T> iterable) {
        int n = 0;
        for (T ignored : iterable) {
            n++;
        }
        return n;
    }
}
