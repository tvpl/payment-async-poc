# Multi-stage build. Context is the repository root.
# Bump the JDK images to 25 when moving the toolchain to Java 25.
FROM gradle:8.14-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle :api-service:runnerJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/api-service/build/libs/api-service-0.1.0-runner.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseZGC", "-jar", "/app/app.jar"]
