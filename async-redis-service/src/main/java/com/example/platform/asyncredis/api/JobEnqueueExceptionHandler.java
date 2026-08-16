package com.example.platform.asyncredis.api;

import com.example.platform.asyncredis.dto.JobResponse;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps a failed stream enqueue (Redis unavailable mid-accept) to 503 — the caller sees a
 * retryable status, not a Lettuce connection string. The infrastructure detail goes to the log,
 * keyed by jobId, not to the response body.
 */
@Produces(MediaType.APPLICATION_JSON)
@Singleton
@Requires(classes = {JobEnqueueException.class, ExceptionHandler.class})
public class JobEnqueueExceptionHandler
        implements ExceptionHandler<JobEnqueueException, HttpResponse<JobResponse>> {

    private static final Logger LOG = LoggerFactory.getLogger(JobEnqueueExceptionHandler.class);

    @Override
    public HttpResponse<JobResponse> handle(HttpRequest request, JobEnqueueException exception) {
        LOG.error("Failed to enqueue jobId={}", exception.jobId(), exception.getCause());
        return HttpResponse.<JobResponse>status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(new JobResponse(exception.jobId(), "UNAVAILABLE", "/jobs/" + exception.jobId(), null));
    }
}
