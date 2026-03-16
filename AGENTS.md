# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository.

## Build & Test Commands

```bash
# Build the plugin JAR (skips tests)
./mvnw package -DskipTests

# Run unit tests only (ReadOnlyPluginIT — uses neo4j-harness, no Docker needed)
./mvnw test

# Run integration tests via Docker Compose (primary path — no Docker-in-Docker issues)
docker build -t neo4j-read-only-plugin-neo4j-test:latest .
docker build -f Dockerfile.testrunner -t neo4j-read-only-plugin-test-runner:latest .
mkdir -p failsafe-reports
docker compose -f docker-compose.test.yml run \
  -v "$(pwd)/failsafe-reports:/build/target/failsafe-reports" \
  --rm test-runner

# Run integration tests locally (requires a host JDK and Docker socket access)
./mvnw verify   # Testcontainers mode — starts Neo4j automatically

# Run a single test class (unit)
./mvnw test -Dtest=ReadOnlyPluginIT

# Run a single IT class (Testcontainers mode)
./mvnw failsafe:integration-test -Dit.test=WriteEnforcementIT
```

## Architecture

The plugin enforces a write-access policy across all Neo4j databases using two Neo4j extension APIs wired together:

```
ExtensionFactory (service-loader entry point)
  └── ReadOnlyPluginExtension  (LifecycleAdapter + DatabaseEventListener)
        ├── start(): registers TransactionEventListener on all existing DBs
        ├── databaseStart(): registers listener on newly created DBs
        ├── databaseShutdown/Drop/Panic(): unregisters listener
        └── ReadOnlyTransactionEventListener
              └── beforeCommit(): inspects TransactionData, throws WriteNotAllowedException
                                  if user doesn't end with "_rw"
```

**Key design decisions:**
- `ExtensionType.GLOBAL` is required so the `DatabaseManagementService` (a DBMS-wide service) is injectable via the `Dependencies` interface.
- The `system` database is always skipped — Neo4j 5.x throws `IllegalArgumentException` if you register a transaction listener there.
- `registeredDatabases` is a `ConcurrentHashMap.newKeySet()` to safely handle concurrent `databaseStart` callbacks.
- Log messages never interpolate property values — only counts and usernames — to prevent log injection.
- `hasWrites()` checks all 8 `TransactionData` iterables; returning `null` from `beforeCommit()` on read-only transactions avoids unnecessary work.

**Service-loader registration:** `src/main/resources/META-INF/services/org.neo4j.kernel.extension.ExtensionFactory` — must stay in sync with the factory class name.

## Test Infrastructure

There are three test layers:

| Layer | Class(es) | Runner | Neo4j |
|---|---|---|---|
| Unit / harness | `ReadOnlyPluginIT` | Surefire (`*Test`, `*IT` via harness) | `neo4j-harness` (embedded) |
| E2E write policy | `WriteEnforcementIT` | Failsafe | Testcontainers or external |
| Injection resilience | `CypherInjectionIT` | Failsafe | Testcontainers or external |

`ContainerSetup` is a shared static initializer used by all `*IT` classes. It selects mode based on the `NEO4J_BOLT_URL` environment variable:
- **Set** → connects to that URL (Docker Compose network mode, used by CI).
- **Unset** → starts a `Neo4jContainer` via Testcontainers (requires host Docker socket).

The `-plugin` classifier JAR (produced by `maven-shade-plugin`) is what gets deployed. The `plugin.jar.path` system property (set by `maven-failsafe-plugin`) tells Testcontainers mode where to find it.

## Neo4j API Notes

These APIs required careful inspection of the actual Neo4j 5.20.0 JARs — do not assume from docs:
- Database lifecycle listener: `org.neo4j.graphdb.event.DatabaseEventListener` (not `DatabaseManagementServiceListener`)
- All 5 methods are abstract with no defaults: `databaseStart`, `databaseShutdown`, `databaseDrop`, `databasePanic`, `databaseCreate` — there is no `databaseStop`
- `TransactionData.getRelationships()` returns `ResourceIterable<Relationship>`, not plain `Iterable`
- `TransactionData` has a `metaData()` method returning `Map<String, Object>` that must be implemented in mocks
- UNION clauses cannot contain write operations in Cypher — use `CALL {}` subquery for write injection tests
