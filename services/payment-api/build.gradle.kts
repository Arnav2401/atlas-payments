plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

group = "com.atlas"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        // Pinned. Gradle provisions this JDK if it is not installed locally.
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")

    // M2 — ledger
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    // Flyway 10+ split database support out of the core jar; without this,
    // Flyway starts and reports "Unsupported Database: PostgreSQL".
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
