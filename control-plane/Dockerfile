FROM eclipse-temurin:25-jdk-alpine@sha256:1e20ead9eec7234db293f1913438090d533ed47c0b9e12dc8c7627a802a44ed6 AS build
WORKDIR /workspace
COPY gradlew gradlew.bat build.gradle settings.gradle ./
COPY gradle ./gradle
COPY gradle.lockfile ./
RUN chmod +x gradlew
COPY src ./src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar

FROM eclipse-temurin:25-jre-alpine@sha256:b7528962e65d43064d8a8b67ba175367896bc948363ea2d3fcd7f10d34e1f048
# Patch CVE-2026-111497 until the upstream runtime image is rebuilt.
RUN apk add --no-cache --upgrade 'brotli-libs=1.2.1-r0'
RUN addgroup -S kubespawn && adduser -S kubespawn -G kubespawn
WORKDIR /app
COPY --from=build --chown=kubespawn:kubespawn /workspace/build/libs/control-plane-0.7.0-SNAPSHOT.jar app.jar
USER kubespawn
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
