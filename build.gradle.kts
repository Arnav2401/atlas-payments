// Versions are declared once here and applied in each service module.
// Spring Boot 3.5.x supports Gradle 7.6.4+/8.4+ — NOT Gradle 9. The wrapper is
// pinned to 8.14.5 for that reason; do not bump it to 9.x while on Boot 3.
plugins {
    id("org.springframework.boot") version "3.5.16" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}
