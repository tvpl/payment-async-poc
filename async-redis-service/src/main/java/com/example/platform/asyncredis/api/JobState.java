package com.example.platform.asyncredis.api;

/** Persisted lifecycle state of an accepted job (RED-01). */
public enum JobState {

    /** Accepted and queryable; the result has not been released yet. */
    PROCESSING,

    /** The worker released a result. Terminal. */
    COMPLETED
}
