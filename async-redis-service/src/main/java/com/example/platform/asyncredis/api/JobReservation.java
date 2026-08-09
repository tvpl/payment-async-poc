package com.example.platform.asyncredis.api;

import io.micronaut.serde.annotation.Serdeable;

/**
 * What is stored, as one atomic value, under {@code idem:<key>}. Identity and fingerprint are
 * written together by a single {@code SET NX}, so no request can observe a key associated with only
 * half the identity.
 */
@Serdeable
public record JobReservation(String jobId, String fingerprint) {
}
