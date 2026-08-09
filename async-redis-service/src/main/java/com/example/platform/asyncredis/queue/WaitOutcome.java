package com.example.platform.asyncredis.queue;

import com.example.platform.asyncredis.dto.JobResult;

/**
 * How a blocking wait for a job result ended (RED-02).
 *
 * <p>"No result yet" and "no capacity to wait" are kept apart on purpose. Both leave the job running
 * and both answer the client with 202, but only one of them means the service is saturated — folding
 * them together hides exactly the signal an operator needs.
 */
public sealed interface WaitOutcome {

    /** The worker released the result inside the budget. */
    record Released(JobResult result) implements WaitOutcome {
    }

    /** The budget elapsed with no result. The job is still queued. */
    record TimedOut() implements WaitOutcome {
    }

    /** The wait pool was saturated, so this request never got to wait. The job is still queued. */
    record NoCapacity() implements WaitOutcome {
    }
}
