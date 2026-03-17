package com.mappedsky.neo4j;

import org.neo4j.annotations.service.ServiceProvider;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.kernel.extension.ExtensionFactory;
import org.neo4j.kernel.extension.ExtensionType;
import org.neo4j.kernel.extension.context.ExtensionContext;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.logging.internal.LogService;

/**
 * Entry point for the Neo4j plugin mechanism.
 *
 * <p>Neo4j discovers this class via the
 * {@code META-INF/services/org.neo4j.kernel.extension.ExtensionFactory}
 * service-loader file (and the {@link ServiceProvider} annotation, which
 * triggers the Neo4j annotation processor to generate that file at
 * compile time).
 *
 * <p>The extension is registered at {@link ExtensionType#GLOBAL} scope so
 * that the injected {@link DatabaseManagementService} – a DBMS-wide service –
 * is available, allowing the listener to be registered on every database.
 */
@ServiceProvider
public class ReadOnlyPluginExtensionFactory
        extends ExtensionFactory<ReadOnlyPluginExtensionFactory.Dependencies> {

    /**
     * Services that Neo4j's dependency-injection framework will resolve and
     * pass to {@link #newInstance}.  Method names must exactly match the
     * simple class name (lower-camel) of the requested service type.
     */
    public interface Dependencies {
        DatabaseManagementService databaseManagementService();
        LogService logService();
    }

    public ReadOnlyPluginExtensionFactory() {
        super(ExtensionType.GLOBAL, "neo4j-read-only-plugin");
    }

    @Override
    public Lifecycle newInstance(ExtensionContext context, Dependencies dependencies) {
        return new ReadOnlyPluginExtension(
                dependencies.databaseManagementService(),
                dependencies.logService().getUserLog(ReadOnlyPluginExtension.class));
    }
}
