FROM eclipse-temurin:24-jdk-alpine@sha256:8fdbcb6bc6b846640cea7058e6eeb56c311fae4efaa506a213789134065c6b90 AS build
WORKDIR /workspace
COPY gradlew gradlew.bat build.gradle settings.gradle ./
COPY gradle ./gradle
COPY gradle.lockfile ./
RUN chmod +x gradlew
COPY src ./src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar

FROM eclipse-temurin:24-jre-alpine@sha256:4044b6c87cb088885bcd0220f7dc7a8a4aab76577605fa471945d2e98270741f
# Patch CVE-2026-111497 until the upstream runtime image is rebuilt.
RUN apk add --no-cache --upgrade 'brotli-libs=1.2.1-r0'
RUN addgroup -S kubespawn && adduser -S kubespawn -G kubespawn
WORKDIR /app
COPY --from=build --chown=kubespawn:kubespawn /workspace/build/libs/control-plane-0.7.0-SNAPSHOT.jar app.jar
USER kubespawn
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
