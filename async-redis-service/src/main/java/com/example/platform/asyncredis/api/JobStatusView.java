package com.example.platform.asyncredis.api;

import com.example.platform.asyncredis.dto.JobResult;

/**
 * What a poll can observe about a job (RED-01). The four cases are deliberately distinct: a job that
 * was never accepted is not the same as one still running, and a finished job whose result payload
 * has aged out is not the same as one that never existed.
 */
public sealed interface JobStatusView {

    /** No such job, or its status has aged out entirely. */
    record Unknown() implements JobStatusView {
    }

    /** Accepted and still in flight. */
    record Processing() implements JobStatusView {
    }

    /** Terminal, with the result still retained. */
    record Completed(JobResult result) implements JobStatusView {
    }

    /** Terminal, but the result payload outlived its retention and is gone. */
    record Expired() implements JobStatusView {
    }
}
