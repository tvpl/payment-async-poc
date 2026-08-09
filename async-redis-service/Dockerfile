# syntax=docker/dockerfile:1.7

FROM gradle:8.14.3-jdk21-alpine@sha256:d20561a56ff27350ea778b8151f6af913c76e9d35b6a135f927ee16e3ce8193c AS build

USER root
RUN mkdir -p /workspace && chown -R gradle:gradle /workspace
WORKDIR /workspace

COPY --chown=gradle:gradle gradle/ gradle/
COPY --chown=gradle:gradle gradlew settings.gradle build.gradle gradle.properties ./
COPY --chown=gradle:gradle src/main/ src/main/

USER gradle
RUN ./gradlew installDist --no-daemon

FROM eclipse-temurin:21-jre-alpine@sha256:3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c AS runtime

RUN addgroup -S -g 10001 app \
    && adduser -S -D -H -u 10001 -G app app
WORKDIR /app

COPY --from=build --chown=10001:10001 /workspace/build/install/async-redis-service/ /app/

LABEL org.opencontainers.image.title="async-redis-service" \
      org.opencontainers.image.description="Async-to-sync payment simulation over Redis Streams and BRPOP"

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Djava.io.tmpdir=/tmp"

USER 10001:10001
EXPOSE 8084
HEALTHCHECK --interval=5s --timeout=3s --start-period=20s --retries=12 \
    CMD wget -q --spider http://127.0.0.1:8084/health/liveness || exit 1

ENTRYPOINT ["/app/bin/async-redis-service"]
