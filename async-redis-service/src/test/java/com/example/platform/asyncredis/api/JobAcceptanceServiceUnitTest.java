package com.example.platform.asyncredis.api;

import com.example.platform.asyncredis.config.AsyncRedisProperties;
import com.example.platform.asyncredis.dto.SubmitJobRequest;
import com.example.platform.asyncredis.queue.JobEnqueuer;
import com.example.platform.asyncredis.redis.RedisConnections;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RED-01 is an ordering requirement: the status must be persisted <em>before</em> the job is
 * enqueued. Only a seam between acceptance and the stream can observe that ordering directly — from
 * the outside both writes look simultaneous.
 */
class JobAcceptanceServiceUnitTest {

    private static final SubmitJobRequest REQUEST = new SubmitJobRequest("ORDER-1", 10_000L, "hello");

    /** Records what the store already knew at the moment the job was handed to the stream. */
    private static final class RecordingEnqueuer implements JobEnqueuer {

        private final RecordingStore store;
        private final List<String> enqueued = new ArrayList<>();
        private final List<JobStatusView> statusAtEnqueue = new ArrayList<>();

        private RecordingEnqueuer(RecordingStore store) {
            this.store = store;
        }

        @Override
        public String enqueue(String jobId, SubmitJobRequest request) {
            enqueued.add(jobId);
            statusAtEnqueue.add(store.find(jobId));
            return "0-1";
        }
    }

    /** In-memory stand-in for the Redis-backed store; only ordering is under test here. */
    private static final class RecordingStore extends JobStatusStore {

        private final List<String> processing = new ArrayList<>();

        private RecordingStore() {
            super(new RedisConnections(null, new AsyncRedisProperties()),
                    ObjectMapper.getDefault(), new AsyncRedisProperties());
        }

        @Override
        public void createProcessing(String jobId) {
            processing.add(jobId);
        }

        @Override
        public JobStatusView find(String jobId) {
            return processing.contains(jobId)
                    ? new JobStatusView.Processing()
                    : new JobStatusView.Unknown();
        }
    }

    @Test
    void statusIsQueryableBeforeTheJobReachesTheStream() {
        RecordingStore store = new RecordingStore();
        RecordingEnqueuer enqueuer = new RecordingEnqueuer(store);
        JobAcceptanceService service = new JobAcceptanceService(store, enqueuer);

        AcceptOutcome outcome = service.accept(null, REQUEST);

        String jobId = assertInstanceOf(AcceptOutcome.Accepted.class, outcome).jobId();
        assertEquals(List.of(jobId), enqueuer.enqueued);
        assertEquals(new JobStatusView.Processing(), enqueuer.statusAtEnqueue.get(0));
    }

    @Test
    void aSubmissionWithoutAnIdempotencyKeyStillGetsItsOwnJob() {
        RecordingStore store = new RecordingStore();
        RecordingEnqueuer enqueuer = new RecordingEnqueuer(store);
        JobAcceptanceService service = new JobAcceptanceService(store, enqueuer);

        String first = assertInstanceOf(AcceptOutcome.Accepted.class, service.accept(null, REQUEST)).jobId();
        String second = assertInstanceOf(AcceptOutcome.Accepted.class, service.accept("  ", REQUEST)).jobId();

        assertNotEquals(first, second);
        assertEquals(2, enqueuer.enqueued.size());
        assertTrue(store.processing.containsAll(List.of(first, second)));
    }
}
