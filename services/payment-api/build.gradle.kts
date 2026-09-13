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

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
