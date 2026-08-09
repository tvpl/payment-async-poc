package com.example.platform.asyncredis.queue;

import com.example.platform.asyncredis.dto.SubmitJobRequest;

/**
 * Publishing seam between job acceptance and the stream. Acceptance depends on this narrow contract
 * rather than the whole queue, so the ordering it must guarantee — status persisted before the job
 * is enqueued (RED-01) — is directly observable in a test.
 */
public interface JobEnqueuer {

    /** Publishes the job onto the stream. Returns the stream message id. */
    String enqueue(String jobId, SubmitJobRequest request);
}
