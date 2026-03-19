package com.mappedsky.neo4j.agent;

import net.bytebuddy.asm.Advice;

/**
 * Byte Buddy advice that blocks administrative commands on the Neo4j system database
 * for users who lack the {@code _admin} username suffix.
 *
 * <p>Inlined into {@code UpdatingSystemCommandExecutionPlanBase.runSpecific}, which is
 * the outermost execution entry-point for all write admin commands (CREATE USER,
 * DROP USER, ALTER USER, CREATE ROLE, etc.) and is called before any inner subquery
 * executes — ensuring that the exception propagates cleanly to the Bolt protocol layer.
 *
 * <p>All Neo4j types are accessed via reflection to avoid compile-time classloader binding.
 */
public class AdminCommandAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.AllArguments Object[] args) {
        // args[0] = SystemUpdateCountingQueryContext (may also be called via bridge overload)
        if (args == null || args.length == 0 || args[0] == null) return;

        try {
            // ── Extract SecurityContext ─────────────────────────────────────────────
            Object qctx = args[0];

            java.lang.reflect.Method ktcMethod = null;
            for (java.lang.reflect.Method m : qctx.getClass().getMethods()) {
                if ("kernelTransactionalContext".equals(m.getName())
                        && m.getParameterCount() == 0) {
                    ktcMethod = m;
                    break;
                }
            }
            if (ktcMethod == null) return; // not the expected overload
            ktcMethod.setAccessible(true);
            Object tc = ktcMethod.invoke(qctx);

            java.lang.reflect.Method scMethod = null;
            for (java.lang.reflect.Method m : tc.getClass().getMethods()) {
                if ("securityContext".equals(m.getName()) && m.getParameterCount() == 0) {
                    scMethod = m;
                    break;
                }
            }
            if (scMethod == null) return;
            scMethod.setAccessible(true);
            Object secCtx = scMethod.invoke(tc);
            if (secCtx == null) return;

            // ── Extract username ────────────────────────────────────────────────────
            java.lang.reflect.Method subjectMethod = null;
            for (java.lang.reflect.Method m : secCtx.getClass().getMethods()) {
                if ("subject".equals(m.getName()) && m.getParameterCount() == 0) {
                    subjectMethod = m;
                    break;
                }
            }
            if (subjectMethod == null) return;
            subjectMethod.setAccessible(true);
            Object subject = subjectMethod.invoke(secCtx);
            if (subject == null) return;

            java.lang.reflect.Method executingUserMethod = null;
            for (java.lang.reflect.Method m : subject.getClass().getMethods()) {
                if ("executingUser".equals(m.getName()) && m.getParameterCount() == 0) {
                    executingUserMethod = m;
                    break;
                }
            }
            if (executingUserMethod == null) return;
            executingUserMethod.setAccessible(true);
            String username = (String) executingUserMethod.invoke(subject);
            if (username == null) return;

            // ── Enforce policy ──────────────────────────────────────────────────────
            // Allow: _admin suffix, built-in neo4j bootstrap user, and empty-username
            // contexts (AuthSubject.AUTH_DISABLED / ANONYMOUS — no-auth mode or
            // internal subjects that return "" — let Neo4j's own access control decide).
            if (username.endsWith("_admin") || "neo4j".equals(username)
                    || username.isEmpty()) {
                return;
            }

            Class<?> exClass = Class.forName(
                    "org.neo4j.graphdb.security.AuthorizationViolationException",
                    true, secCtx.getClass().getClassLoader());
            RuntimeException ex = (RuntimeException)
                    exClass.getConstructor(String.class).newInstance(
                            "User '" + username + "' is not permitted to execute "
                            + "administrative commands. Administrative access requires "
                            + "a username ending in '_admin'.");
            throw ex;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // Fail-open: unexpected reflection error; let Neo4j decide.
        }
    }
}
