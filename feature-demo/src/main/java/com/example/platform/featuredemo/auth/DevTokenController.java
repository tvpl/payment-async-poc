package com.example.platform.featuredemo.auth;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.token.generator.TokenGenerator;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dev-only JWT issuer so the scenarios can be exercised with curl. Mints an HS256 token carrying
 * {@code sub} and the requested groups (as both {@code roles} and a {@code groups} claim, covering
 * role-based and group-based tokens). <strong>Not for production</strong> — real tokens come from
 * your IdP; this just makes the demo self-contained.
 */
@Controller("/auth")
@Secured(SecurityRule.IS_ANONYMOUS)
public class DevTokenController {

    private final TokenGenerator tokenGenerator;

    public DevTokenController(TokenGenerator tokenGenerator) {
        this.tokenGenerator = tokenGenerator;
    }

    @Serdeable
    public record TokenRequest(@NotBlank String userId, List<String> groups) {
    }

    @Serdeable
    public record TokenResponse(String accessToken, String tokenType, String userId, List<String> groups) {
    }

    @Post("/token")
    public HttpResponse<TokenResponse> issue(@Body TokenRequest request) {
        List<String> groups = request.groups() == null ? List.of() : request.groups();
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", request.userId());
        claims.put("roles", groups);
        claims.put("groups", groups);
        claims.put("iat", Instant.now().getEpochSecond());
        claims.put("exp", Instant.now().plusSeconds(3600).getEpochSecond());

        return tokenGenerator.generateToken(claims)
                .map(token -> HttpResponse.ok(
                        new TokenResponse(token, "Bearer", request.userId(), groups)))
                .orElseGet(HttpResponse::serverError);
    }
}
