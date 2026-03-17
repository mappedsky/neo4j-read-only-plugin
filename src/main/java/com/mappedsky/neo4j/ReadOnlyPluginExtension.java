package com.mappedsky.neo4j;

import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.graphdb.event.DatabaseEventContext;
import org.neo4j.graphdb.event.DatabaseEventListener;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.logging.Log;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lifecycle component that registers the {@link ReadOnlyTransactionEventListener}
 * on every database – both those that exist at startup and any created later.
 *
 * <p>It also implements {@link DatabaseManagementServiceListener} so that
 * whenever Neo4j starts a new database the transaction-event listener is
 * registered automatically, and whenever a database stops it is cleanly
 * unregistered.
 */
public class ReadOnlyPluginExtension extends LifecycleAdapter
        implements DatabaseEventListener {

    private final DatabaseManagementService managementService;
    private final ReadOnlyTransactionEventListener listener;
    private final Log log;

    /**
     * Tracks which databases currently have the listener registered.
     * Needs to be thread-safe: {@link DatabaseManagementServiceListener}
     * callbacks can arrive on any thread.
     */
    private final Set<String> registeredDatabases = ConcurrentHashMap.newKeySet();

    public ReadOnlyPluginExtension(DatabaseManagementService managementService, Log log) {
        this.managementService = managementService;
        this.log = log;
        this.listener = new ReadOnlyTransactionEventListener(log);
    }

    // -------------------------------------------------------------------------
    // LifecycleAdapter
    // -------------------------------------------------------------------------

    @Override
    public void start() {
        // Cover databases that already exist.
        for (String dbName : managementService.listDatabases()) {
            registerOn(dbName);
        }

        // Subscribe to future database lifecycle events so that databases
        // created after this point are also covered.
        managementService.registerDatabaseEventListener(this);
        log.info("ReadOnlyPlugin: registered database-event listener for dynamic database tracking");
    }

    @Override
    public void stop() {
        managementService.unregisterDatabaseEventListener(this);
        log.info("ReadOnlyPlugin: unregistered database-event listener");

        // Unregister from any databases that are still tracked (e.g. if a
        // database did not emit a databaseStop event before DBMS shutdown).
        for (String dbName : registeredDatabases) {
            unregisterFrom(dbName);
        }
    }

    // -------------------------------------------------------------------------
    // DatabaseManagementServiceListener – dynamic database coverage
    // -------------------------------------------------------------------------

    /**
     * Called when a database transitions to the STARTED state.
     * Registers the transaction-event listener so writes are gated from the
     * very first transaction.
     */
    @Override
    public void databaseStart(DatabaseEventContext eventContext) {
        registerOn(eventContext.getDatabaseName());
    }

    /**
     * Called when a database shuts down cleanly.
     * Unregisters the transaction-event listener.
     */
    @Override
    public void databaseShutdown(DatabaseEventContext eventContext) {
        unregisterFrom(eventContext.getDatabaseName());
    }

    /**
     * Called when a database is dropped.
     * Unregisters the transaction-event listener.
     */
    @Override
    public void databaseDrop(DatabaseEventContext eventContext) {
        unregisterFrom(eventContext.getDatabaseName());
    }

    /**
     * Called when a database enters a panic state.
     * Unregisters the transaction-event listener so no further commits are attempted.
     */
    @Override
    public void databasePanic(DatabaseEventContext eventContext) {
        unregisterFrom(eventContext.getDatabaseName());
    }

    /** Called when a database is created – the subsequent {@link #databaseStart} will register the listener. */
    @Override
    public void databaseCreate(DatabaseEventContext eventContext) {
        // registration happens in databaseStart once the database is actually running
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void registerOn(String dbName) {
        // Neo4j does not support transaction event listeners on the system database.
        if ("system".equalsIgnoreCase(dbName)) {
            log.info("ReadOnlyPlugin: skipping system database (transaction event listeners not supported)");
            return;
        }
        if (registeredDatabases.add(dbName)) {
            managementService.registerTransactionEventListener(dbName, listener);
            log.info("ReadOnlyPlugin: registered transaction event listener on database '%s'", dbName);
        }
    }

    private void unregisterFrom(String dbName) {
        if (registeredDatabases.remove(dbName)) {
            managementService.unregisterTransactionEventListener(dbName, listener);
            log.info("ReadOnlyPlugin: unregistered transaction event listener from database '%s'", dbName);
        }
    }
}
