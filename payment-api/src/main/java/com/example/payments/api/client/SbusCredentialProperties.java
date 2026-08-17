package com.example.payments.api.client;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.time.Duration;

/**
 * The Edge's own service credential for calling the SBUS internal status endpoint (SEC-05).
 *
 * <p>Exactly one of two mechanisms is expected to be configured, matching the dev/prod split
 * already used for the Edge's own inbound JWT validation ({@code application-dev.yml} /
 * {@code application-prod.yml}):
 * <ul>
 *   <li>{@link #secret} — dev/local only. An HS256 secret shared with the SBUS's own dev
 *       signing config ({@code SBUS_DEV_JWT_SECRET} on both boundaries); {@link
 *       SbusServiceTokenProvider} mints a short-lived {@code ROLE_PAYMENT_API} JWT per call.</li>
 *   <li>{@link #token} — a pre-minted token supplied by external credential management (prod),
 *       used verbatim.</li>
 * </ul>
 * If neither is set, {@link SbusServiceTokenProvider} returns no token and the call goes out
 * unauthenticated, failing/degrading exactly like any other SBUS outage.
 */
@ConfigurationProperties("payment.sbus.credential")
public class SbusCredentialProperties {

    private String secret;
    private String token;
    private String subject = "payment-api";
    private Duration ttl = Duration.ofMinutes(5);

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }
}
