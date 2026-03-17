package com.mappedsky.neo4j;

import org.neo4j.logging.Log;

/**
 * A {@link Log} implementation that silently discards all messages.
 * Used in unit tests to avoid needing a real {@code LogService}.
 */
class NoOpLog implements Log {

    static final NoOpLog INSTANCE = new NoOpLog();

    private NoOpLog() {}

    @Override public boolean isDebugEnabled() { return false; }
    @Override public void debug(String message) {}
    @Override public void debug(String message, Throwable throwable) {}
    @Override public void debug(String format, Object... arguments) {}
    @Override public void info(String message) {}
    @Override public void info(String message, Throwable throwable) {}
    @Override public void info(String format, Object... arguments) {}
    @Override public void warn(String message) {}
    @Override public void warn(String message, Throwable throwable) {}
    @Override public void warn(String format, Object... arguments) {}
    @Override public void error(String message) {}
    @Override public void error(String message, Throwable throwable) {}
    @Override public void error(String format, Object... arguments) {}
}
