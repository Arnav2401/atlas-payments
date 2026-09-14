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

// This is an application, not a library nothing else depends on as a jar -
// the plain (non-executable) jar the `java` plugin produces alongside
// bootJar exists for library consumers, which this project has none of.
// Disabling it means `build/libs/*.jar` is unambiguous, which is what
// Dockerfile's COPY step relies on rather than hardcoding a version string
// that would go stale the moment `version` above changes.
tasks.named<Jar>("jar") {
    enabled = false
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

    // M3 — fraud service client
    implementation("org.springframework.boot:spring-boot-starter-aop") // required for @CircuitBreaker's AOP proxy
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.4.0")

    // M4 — transactional outbox + Kafka. Version managed by Spring Boot's
    // dependency-management BOM (already applied), not pinned here, the same
    // way spring-boot-starter-data-jpa's Hibernate version isn't pinned —
    // this is the version Boot 3.5.16 was actually tested against.
    implementation("org.springframework.kafka:spring-kafka")

    // M5 — auth. Resource-server support (JwtDecoder + bearer-token
    // filtering) is used for VALIDATING incoming tokens; this service also
    // ISSUES its own tokens (see JwtService), for which the resource-server
    // starter already pulls in Nimbus JOSE+JWT transitively — one JWT
    // library for both directions, not two.
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // M5 — observability
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    // JSON structured logging, correlated by endToEndId via MDC — see logback-spring.xml.
    implementation("net.logstash.logback:logstash-logback-encoder:8.1")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    // Note: the com.github.tomakehurst:wiremock-jre8 coordinate stopped at
    // 3.0.1; the project moved to org.wiremock:* from 3.x onward. The plain
    // "wiremock" artifact does not bundle a Jetty server implementation and
    // fails at startup ("Jetty 11 is not present") unless one is added
    // separately; wiremock-standalone is the shaded, batteries-included jar.
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
    testImplementation("org.awaitility:awaitility:4.3.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
