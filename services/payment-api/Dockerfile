# Multi-stage: the build stage needs the Gradle wrapper and full source tree;
# the runtime image needs only the built jar and a JRE, not a JDK or the
# build toolchain at all — keeps the shipped image meaningfully smaller and
# without a compiler in it.
FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace
# Copy the wrapper and build files first so Gradle's dependency download is
# its own Docker layer, cached across rebuilds that only change source code —
# without this ordering, editing one Java file invalidates the dependency
# download too, which is the difference between a 5-second rebuild and a
# multi-minute one.
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
# Unambiguous: build.gradle.kts disables the plain (non-executable) jar the
# `java` plugin would otherwise also produce here, so this glob matches
# exactly one file regardless of what `version` is set to.
COPY --from=build /workspace/services/payment-api/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
