package com.example.platform.asyncredis.api;

/**
 * Thrown when the stream {@code XADD} fails after the idempotency reservation and PROCESSING
 * status were already persisted (RED-08). The jobId is retained so a caller can be told where to
 * poll — a status the store now records as {@link JobState#ENQUEUE_FAILED} rather than one that
 * silently looks PROCESSING forever. The cause (a Lettuce/Redis exception) stays out of the HTTP
 * response; see {@link JobEnqueueExceptionHandler}.
 */
public class JobEnqueueException extends RuntimeException {

    private final String jobId;

    public JobEnqueueException(String jobId, Throwable cause) {
        super("Failed to enqueue job " + jobId, cause);
        this.jobId = jobId;
    }

    public String jobId() {
        return jobId;
    }
}
