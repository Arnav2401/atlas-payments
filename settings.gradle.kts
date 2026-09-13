plugins {
    // This machine has no JDK 21 (only 24). The Foojay resolver lets Gradle
    // download and cache the Java 21 toolchain itself on first build, so the
    // build never silently compiles against whatever JDK happens to be on PATH.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "atlas-payments"

include("services:payment-api")
