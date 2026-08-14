package com.example.platform.asyncredis.controller;

import com.example.platform.asyncredis.api.AcceptOutcome;
import com.example.platform.asyncredis.api.JobAcceptanceService;
import com.example.platform.asyncredis.api.JobStatusStore;
import com.example.platform.asyncredis.api.JobStatusView;
import com.example.platform.asyncredis.config.AsyncRedisProperties;
import com.example.platform.asyncredis.dto.JobResponse;
import com.example.platform.asyncredis.dto.SubmitJobRequest;
import com.example.platform.asyncredis.queue.JobQueue;
import com.example.platform.asyncredis.queue.WaitOutcome;
import com.example.platform.asyncredis.ratelimit.AsyncRateLimiter;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;

/**
 * HTTP entry point for the Kafka-free async->sync example.
 *
 * <p>{@code POST /jobs} persists a queryable {@code PROCESSING} status, enqueues the work on a Redis
 * Stream, and then <strong>blocks on a virtual thread</strong> (via {@code @ExecuteOn(BLOCKING)})
 * doing a BRPOP on the per-request response list. If the answer arrives within the timeout the
 * client gets {@code 200 COMPLETED}; otherwise {@code 202 PROCESSING} with a {@code statusUrl}.
 *
 * <p>Polling reports four distinct outcomes (RED-01): {@code 404 UNKNOWN} for a job that was never
 * accepted, {@code 202 PROCESSING} while it is in flight, {@code 200 COMPLETED} with the result, and
 * {@code 410 EXPIRED} once a finished job's result has aged out.
 */
@Controller("/jobs")
public class AsyncJobController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    static final String BACKPRESSURE_HEADER = "X-Backpressure";

    private final JobQueue queue;
    private final JobAcceptanceService acceptance;
    private final JobStatusStore store;
    private final AsyncRateLimiter rateLimiter;
    private final AsyncRedisProperties props;

    public AsyncJobController(JobQueue queue,
                              JobAcceptanceService acceptance,
                              JobStatusStore store,
                              AsyncRateLimiter rateLimiter,
                              AsyncRedisProperties props) {
        this.queue = queue;
        this.acceptance = acceptance;
        this.store = store;
        this.rateLimiter = rateLimiter;
        this.props = props;
    }

    @Post
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<JobResponse> submit(
            @Nullable @Header(IDEMPOTENCY_HEADER) String idempotencyKey,
            @Valid @Body SubmitJobRequest request) {

        if (!rateLimiter.tryAcquire()) {
            // Backpressure: shed load before it piles onto the workers.
            return HttpResponse.<JobResponse>status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "1");
        }
        boolean keyMissing = idempotencyKey == null || idempotencyKey.isBlank();
        if (props.isIdempotencyRequired() && keyMissing) {
            return HttpResponse.<JobResponse>status(HttpStatus.BAD_REQUEST)
                    .body(new JobResponse(null, "IDEMPOTENCY_KEY_REQUIRED", null, null));
        }

        AcceptOutcome outcome = acceptance.accept(idempotencyKey, request);
        if (outcome instanceof AcceptOutcome.Conflict conflict) {
            return HttpResponse.<JobResponse>status(HttpStatus.CONFLICT)
                    .body(new JobResponse(conflict.jobId(), "CONFLICT", statusUrl(conflict.jobId()), null));
        }
        if (outcome instanceof AcceptOutcome.Replay replay) {
            // The key already owns a job; report where that one stands instead of waiting on a
            // wakeup that belongs to the original caller.
            return statusResponse(replay.jobId());
        }

        String jobId = ((AcceptOutcome.Accepted) outcome).jobId();
        JobResponse processing = new JobResponse(jobId, "PROCESSING", statusUrl(jobId), null);
        return switch (queue.awaitResult(jobId)) {
            case WaitOutcome.Released released ->
                    HttpResponse.ok(new JobResponse(
                            jobId, "COMPLETED", statusUrl(jobId), released.result()));
            case WaitOutcome.TimedOut ignored ->
                    HttpResponse.<JobResponse>accepted().body(processing);
            // The job is queued and will be processed; what ran out was the capacity to wait for it.
            // Saying so explicitly is what separates saturation from an ordinary slow worker.
            case WaitOutcome.NoCapacity ignored ->
                    HttpResponse.<JobResponse>accepted()
                            .header(BACKPRESSURE_HEADER, "wait-pool-exhausted")
                            .header("Retry-After", "1")
                            .body(processing);
        };
    }

    @Get("/{jobId}")
    public HttpResponse<JobResponse> get(@PathVariable String jobId) {
        return statusResponse(jobId);
    }

    private HttpResponse<JobResponse> statusResponse(String jobId) {
        String url = statusUrl(jobId);
        return switch (store.find(jobId)) {
            case JobStatusView.Completed completed ->
                    HttpResponse.ok(new JobResponse(jobId, "COMPLETED", url, completed.result()));
            case JobStatusView.Processing ignored ->
                    HttpResponse.<JobResponse>accepted()
                            .body(new JobResponse(jobId, "PROCESSING", url, null));
            case JobStatusView.Expired ignored ->
                    HttpResponse.<JobResponse>status(HttpStatus.GONE)
                            .body(new JobResponse(jobId, "EXPIRED", url, null));
            case JobStatusView.Failed ignored ->
                    // Terminal: the worker gave up and routed this job to the DLQ. The client needs
                    // an observable terminal state instead of the job silently aging out to UNKNOWN
                    // once its status-ttl expires (AUD-13).
                    HttpResponse.ok(new JobResponse(jobId, "FAILED", url, null));
            case JobStatusView.EnqueueFailed ignored ->
                    // The reservation exists but the stream write never landed. Retrying the
                    // original POST with the same Idempotency-Key re-attempts the enqueue
                    // (JobAcceptanceService); a plain GET here can only report the truth.
                    HttpResponse.<JobResponse>status(HttpStatus.SERVICE_UNAVAILABLE)
                            .header("Retry-After", "1")
                            .body(new JobResponse(jobId, "UNAVAILABLE", url, null));
            case JobStatusView.Unknown ignored ->
                    HttpResponse.<JobResponse>notFound()
                            .body(new JobResponse(jobId, "UNKNOWN", url, null));
        };
    }

    private static String statusUrl(String jobId) {
        return "/jobs/" + jobId;
    }
}
