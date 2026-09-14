# Multi-stage so the runtime image carries a JRE and the jar, not a JDK and the
# whole build toolchain.
FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace
# Build files first, so the dependency download caches in its own layer and a
# source edit doesn't invalidate it.
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY services/payment-api/build.gradle.kts services/payment-api/build.gradle.kts
RUN ./gradlew :services:payment-api:dependencies --no-daemon > /dev/null 2>&1 || true

COPY services/payment-api services/payment-api
RUN ./gradlew :services:payment-api:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre AS runtime

RUN useradd --system atlas
USER atlas

WORKDIR /app
# build.gradle.kts disables the plain jar, so this glob matches exactly one file.
COPY --from=build /workspace/services/payment-api/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
