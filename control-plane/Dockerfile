FROM eclipse-temurin:21-jdk-alpine@sha256:fd52084c35bbac78890d95d57707d31ffc9d7a3324199e89c06be2a9dc71bfa0 AS build
WORKDIR /workspace
COPY gradlew gradlew.bat build.gradle settings.gradle ./
COPY gradle ./gradle
COPY gradle.lockfile ./
RUN chmod +x gradlew
COPY src ./src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-alpine@sha256:b48e02e0ea88d7bfb305335df94923bdef394905f4147dccd52e873cf17b1088
RUN addgroup -S kubespawn && adduser -S kubespawn -G kubespawn
WORKDIR /app
COPY --from=build --chown=kubespawn:kubespawn /workspace/build/libs/control-plane-0.1.0-SNAPSHOT.jar app.jar
USER kubespawn
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
