package com.example.platform.asyncredis.retention;

/**
 * One retention check's outcome (RED-03).
 *
 * @param serverVersion the connected Redis's {@code redis_version}, or {@code null} when it could
 *                       not be read
 * @param ackedTrimSupported whether that server is new enough (>= {@link
 *                           StreamRetentionMonitor#MIN_ACKED_TRIM_VERSION}) to ship the
 *                           PEL-aware {@code ACKED} trim strategy
 * @param streamLength the job stream's length ({@code XLEN}) at the moment of the check
 * @param alertThreshold the backlog size ({@code stream-maxlen * retention-alert-threshold}) at or
 *                        above which {@code backlogAlert} turns on
 * @param backlogAlert whether {@code streamLength} has reached the safe backlog budget
 */
public record RetentionStatus(String serverVersion, boolean ackedTrimSupported, long streamLength,
                              long alertThreshold, boolean backlogAlert) {
}
