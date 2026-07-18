# Single multi-stage build for all three services.
#
# BuildKit builds the shared `build` stage once (one Gradle invocation compiles the
# whole project) and reuses it for each runtime target, so `docker compose build`
# compiles a single time instead of once per service.
#
# Bump the JDK images to 25 when moving the toolchain to Java 25.
FROM gradle:8.14-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle :api-service:runnerJar :sbus-service:runnerJar :core-mock:runnerJar \
           :feature-demo:runnerJar :async-redis-service:runnerJar :pilot-app:runnerJar --no-daemon

# Common runtime base — curl is used by the compose healthchecks (GET /health).
FROM eclipse-temurin:21-jre AS runtime-base
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app

FROM runtime-base AS api
COPY --from=build /workspace/api-service/build/libs/api-service-0.1.0-runner.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseZGC", "-jar", "/app/app.jar"]

FROM runtime-base AS sbus
COPY --from=build /workspace/sbus-service/build/libs/sbus-service-0.1.0-runner.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime-base AS core
COPY --from=build /workspace/core-mock/build/libs/core-mock-0.1.0-runner.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime-base AS feature-demo
COPY --from=build /workspace/feature-demo/build/libs/feature-demo-0.1.0-runner.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime-base AS async-redis
COPY --from=build /workspace/async-redis-service/build/libs/async-redis-service-0.1.0-runner.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-XX:+UseZGC", "-jar", "/app/app.jar"]

FROM runtime-base AS pilot
COPY --from=build /workspace/pilot-app/build/libs/pilot-app-0.1.0-runner.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
