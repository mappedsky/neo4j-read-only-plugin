package com.mappedsky.neo4j;

/**
 * Thrown from {@link ReadOnlyTransactionEventListener#beforeCommit} when a user
 * who does not have a username ending in {@code _rw} attempts to commit a
 * write transaction.  Throwing from {@code beforeCommit} causes Neo4j to roll
 * back the transaction and surface this exception to the caller.
 */
public class WriteNotAllowedException extends Exception {

    public WriteNotAllowedException(String message) {
        super(message);
    }
}
