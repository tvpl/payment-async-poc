package com.example.platform.asyncredis.controller;

import com.example.platform.asyncredis.dto.JobResponse;
import com.example.platform.asyncredis.dto.JobResult;
import com.example.platform.asyncredis.dto.SubmitJobRequest;
import com.example.platform.asyncredis.queue.JobQueue;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;

import java.util.Optional;
import java.util.UUID;

/**
 * HTTP entry point for the Kafka-free async->sync example.
 *
 * <p>{@code POST /jobs} enqueues the work on a Redis Stream and then <strong>blocks on a virtual
 * thread</strong> (via {@code @ExecuteOn(BLOCKING)}) doing a BRPOP on the per-request response list —
 * i.e. it monitors Redis until the worker releases the answer. If it arrives within the timeout the
 * client gets {@code 200 COMPLETED}; otherwise {@code 202 PROCESSING} with a {@code statusUrl} to poll.
 * The mechanic mirrors the Kafka-based flow's UX with Redis as the only moving part.
 */
@Controller("/jobs")
public class AsyncJobController {

    private final JobQueue queue;

    public AsyncJobController(JobQueue queue) {
        this.queue = queue;
    }

    @Post
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<JobResponse> submit(@Valid @Body SubmitJobRequest request) {
        String jobId = UUID.randomUUID().toString();
        queue.enqueue(jobId, request);

        Optional<JobResult> result = queue.awaitResult(jobId);
        String statusUrl = "/jobs/" + jobId;
        return result
                .map(r -> HttpResponse.ok(new JobResponse(jobId, "COMPLETED", statusUrl, r)))
                .orElseGet(() -> HttpResponse.accepted()
                        .body(new JobResponse(jobId, "PROCESSING", statusUrl, null)));
    }

    @Get("/{jobId}")
    public HttpResponse<JobResponse> get(@PathVariable String jobId) {
        return queue.findResult(jobId)
                .map(r -> HttpResponse.ok(new JobResponse(jobId, "COMPLETED", "/jobs/" + jobId, r)))
                .orElseGet(() -> HttpResponse.<JobResponse>notFound()
                        .body(new JobResponse(jobId, "UNKNOWN", "/jobs/" + jobId, null)));
    }
}
