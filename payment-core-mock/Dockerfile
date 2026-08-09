# syntax=docker/dockerfile:1.7

FROM gradle:8.14.3-jdk21-alpine@sha256:d20561a56ff27350ea778b8151f6af913c76e9d35b6a135f927ee16e3ce8193c AS build

USER root
RUN mkdir -p /workspace /contracts-repository \
    && chown -R gradle:gradle /workspace /contracts-repository
WORKDIR /workspace

COPY --from=contracts-repository --chown=gradle:gradle / /contracts-repository/
COPY --chown=gradle:gradle gradle/ gradle/
COPY --chown=gradle:gradle gradlew settings.gradle build.gradle gradle.properties ./
COPY --chown=gradle:gradle src/main/ src/main/

USER gradle
RUN ./gradlew installDist \
    -PcontractsRepository=file:///contracts-repository \
    --no-daemon

FROM eclipse-temurin:21-jre-alpine@sha256:3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c AS runtime

RUN addgroup -S -g 10001 app \
    && adduser -S -D -H -u 10001 -G app app
WORKDIR /app

COPY --from=build --chown=10001:10001 /workspace/build/install/payment-core-mock/ /app/

LABEL org.opencontainers.image.title="payment-core-mock" \
      org.opencontainers.image.description="Deterministic payment Core simulator; NON_PRODUCTION" \
      com.example.lifecycle="NON_PRODUCTION"

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Djava.io.tmpdir=/tmp" \
    CORE_MOCK_CLASSIFICATION="NON_PRODUCTION"

USER 10001:10001
EXPOSE 8082
HEALTHCHECK --interval=5s --timeout=3s --start-period=15s --retries=12 \
    CMD wget -q --spider http://127.0.0.1:8082/health || exit 1

ENTRYPOINT ["/app/bin/payment-core-mock"]
