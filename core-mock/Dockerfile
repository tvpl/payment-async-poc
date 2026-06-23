# Multi-stage build. Context is the repository root.
FROM gradle:8.14-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle :core-mock:runnerJar --no-daemon

FROM eclipse-temurin:21-jre
# curl is used by the compose healthcheck (GET /health).
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/core-mock/build/libs/core-mock-0.1.0-runner.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
