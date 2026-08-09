package com.example.platform.asyncredis.api;

/** Single place where this service's Redis key layout is defined. */
public final class JobKeys {

    private JobKeys() {
    }

    /** Lifecycle state of an accepted job. Outlives the result so "expired" stays distinguishable. */
    public static String status(String jobId) {
        return "job:" + jobId + ":status";
    }

    /** The durable result payload, retained for {@code result-ttl}. */
    public static String result(String jobId) {
        return "job:" + jobId + ":result";
    }

    /** Per-request wakeup list the blocked POST pops with BRPOP. */
    public static String response(String jobId) {
        return "resp:" + jobId;
    }

    /**
     * One-time gate for the wakeup push (RED-06). Its first {@code SET NX} is what makes a
     * redelivered result release idempotent: only that caller pushes to {@link #response}.
     */
    public static String responseSent(String jobId) {
        return "resp:" + jobId + ":sent";
    }

    /** Idempotency reservation for a caller-supplied key. */
    public static String reservation(String idempotencyKey) {
        return "idem:" + idempotencyKey;
    }

    /** Lease naming the single worker allowed to scan and reclaim pending entries for a group. */
    public static String reclaimLease(String group) {
        return "reclaim:" + group + ":owner";
    }
}
