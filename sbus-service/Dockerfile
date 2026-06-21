# Multi-stage build. Context is the repository root.
FROM gradle:8.14-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle :sbus-service:runnerJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/sbus-service/build/libs/sbus-service-0.1.0-runner.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
