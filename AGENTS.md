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

## System Database Guard Agent

The agent (loaded via `-javaagent:`) blocks write administrative commands (`CREATE USER`, `DROP USER`, `ALTER USER`, etc.) for users whose username does not end with `_admin`. It is implemented in `src/main/java/com/mappedsky/neo4j/agent/` and compiled into a separate shaded JAR (`-agent` classifier).

### Policy

| Username | Data DB writes | System DB admin commands |
|---|---|---|
| ends with `_admin` | ✓ allowed | ✓ allowed |
| ends with `_rw` | ✓ allowed | ✗ blocked |
| anything else | ✗ blocked | ✗ blocked |
| `neo4j` (built-in) | ✓ allowed | ✓ allowed (bootstrap) |
| `""` empty / `AUTH_DISABLED` | pass-through | pass-through (no-auth mode) |

### Interception point: why `UpdatingSystemCommandExecutionPlanBase.runSpecific`

**This is the most likely thing to break across Neo4j version upgrades.**

The agent intercepts `org.neo4j.cypher.internal.procs.UpdatingSystemCommandExecutionPlanBase.runSpecific`. This is the outermost Cypher-level entry point for all write admin commands — it is called before any inner subquery executes, so a thrown exception propagates cleanly through `ChainedExecutionPlan.run` to the Bolt protocol layer and is returned to the client as `Neo.ClientError.Security.Forbidden`.

#### Approaches that were tried and failed

**`CommunityAdministrationCommandRuntime.checkActions`** — the original plan. This was the wrong target for two reasons:

1. `checkActions(Seq<DbmsAction>, SecurityContext)` returns `Seq<Tuple2<DbmsAction, PermissionState>>` — it is a data-returning method, not a throwing gate. Neo4j's caller (`AuthorizationAndPredicateExecutionPlan`) reads the returned `PermissionState` values to decide whether to block.

2. Even after fixing (1) by throwing anyway, the exception is swallowed. `checkActions` is called inside `InternalExecutionResult.consumeAll()`, which is wrapped by a `catch (java.lang.Throwable)` handler in `UpdatingSystemCommandExecutionPlanBase.$anonfun$runSpecific$2` (exception table entry: from=71, to=78, target=81, type=`java.lang.Throwable`). The handler does `pop; goto 85` — silently discarding the exception. By the time `checkActions` runs, the `CREATE USER` subquery has already executed; we were only blocking the result drain.

The confirmed exception-swallowing catch block (Neo4j 5.20.0, `UpdatingSystemCommandExecutionPlanBase.class`):
```
// bytecode offsets in $anonfun$runSpecific$2:
71: aload 9
73: invokeinterface InternalExecutionResult.consumeAll()  ← throws our exception
78: goto 85
81: pop                                                   ← Throwable caught, discarded
82: goto 85                                               ← execution continues normally
```

#### How to reverify the interception point for a new Neo4j version

When upgrading Neo4j, run the `SystemDatabaseGuardIT` tests first. If they fail with "Expecting code to raise a throwable" (no exception reaches the client), the interception point has moved. To diagnose:

```bash
# 1. Confirm UpdatingSystemCommandExecutionPlanBase still exists
jar tf ~/.m2/repository/org/neo4j/neo4j-cypher/X.Y.Z/neo4j-cypher-X.Y.Z.jar \
  | grep UpdatingSystemCommandExecutionPlanBase

# 2. Inspect runSpecific signature — check arg 0 type hasn't changed
JAVA_HOME=... javap -p \
  -classpath ~/.m2/repository/org/neo4j/neo4j-cypher/X.Y.Z/neo4j-cypher-X.Y.Z.jar \
  'org.neo4j.cypher.internal.procs.UpdatingSystemCommandExecutionPlanBase' \
  | grep runSpecific

# 3. Verify the SecurityContext extraction path still works:
#    args[0].kernelTransactionalContext().securityContext().subject().executingUser()
#    If any method is renamed, add a System.err.println diagnostic in the advice
#    to trace the actual method names at runtime (see diagnostic approach below).

# 4. Verify checkActions still exists (for context on the auth flow)
jar tf ~/.m2/repository/org/neo4j/neo4j-cypher/X.Y.Z/neo4j-cypher-X.Y.Z.jar \
  | grep CommunityAdministrationCommandRuntime
```

If `UpdatingSystemCommandExecutionPlanBase` is renamed or removed, look for the class that:
- Has a `runSpecific` method taking a `SystemUpdateCountingQueryContext` (or equivalent) as arg 0
- Calls `normalExecutionEngine.executeSubquery(...)` to run the internal admin Cypher
- Is extended by `UpdatingSystemCommandExecutionPlan`

#### Diagnostic approach for debugging agent issues

Add `System.err.println("[GUARD] ...")` traces to `AdminCommandAdvice.onEnter` to observe which methods are available and what values they return. The Neo4j container logs (`docker compose -f docker-compose.test.yml logs neo4j-test`) will show the output. Key things to trace:

1. **Is the advice even running?** — Print at the very top of `onEnter` before any null checks.
2. **Is `kernelTransactionalContext()` found?** — Print whether `ktcMethod` is null.
3. **What is the username?** — Print the resolved `username` to confirm the correct method is being called.
4. **Is the exception reaching the client?** — If GUARD logs show BLOCKING but tests still fail with "Expecting code to raise a throwable", the exception is being swallowed somewhere. The interception point is too deep in the call stack; move it higher.

#### Key API facts verified against Neo4j 5.20.0 JARs

- `SecurityContext` is a **concrete class** extending `LoginContext` (not an interface). Walk the **superclass chain** (not `getInterfaces()`) to check for it.
- `AuthSubject.executingUser()` is the correct method — there is no `username()` or `getUsername()` on `AuthSubject` in Neo4j 5.x.
- `BasicLoginContext$BasicAuthSubject` is a private inner class. Its public methods are inaccessible to outside classloaders without `setAccessible(true)`.
- `AuthSubject.ANONYMOUS` and `AuthSubject.AUTH_DISABLED` both return `""` for `executingUser()`. The guard passes these through (fail-open) to preserve no-auth mode.
- `AuthorizationViolationException(String)` sets `statusCode = Status.Security.Forbidden` automatically — the single-arg constructor is correct.
- Internal Neo4j kernel operations do **not** go through `UpdatingSystemCommandExecutionPlanBase` — they use the kernel transaction API directly. Only Bolt/HTTP Cypher commands hit this class.
