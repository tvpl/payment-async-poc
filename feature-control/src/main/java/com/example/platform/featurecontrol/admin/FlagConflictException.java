package com.example.platform.featurecontrol.admin;

/**
 * Thrown when an optimistic-concurrency write loses: the caller's {@code version} no longer matches
 * the stored one (another admin wrote first). Controllers map this to HTTP 409 Conflict so the client
 * re-reads and retries instead of silently clobbering the other change.
 */
public class FlagConflictException extends RuntimeException {

    private final long expected;
    private final long actual;

    public FlagConflictException(String flag, long expected, long actual) {
        super("Version conflict on flag '" + flag + "': expected " + expected + " but current is " + actual);
        this.expected = expected;
        this.actual = actual;
    }

    public long expected() {
        return expected;
    }

    public long actual() {
        return actual;
    }
}
