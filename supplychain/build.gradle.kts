/*
 * Supply Chain Orchestration Platform — first real implementation slice.
 *
 * Companion code to the design docs at
 * lib/src/main/java/org/pk/practices/design/supplychain/{DESIGN,LLD}.md.
 * Kept as its own module (rather than inside `lib`) so Postgres/Kafka client
 * jars don't bleed into every other demo's classpath.
 */

plugins {
    `java-library`
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // REST (Javalin + embedded Jetty) — same choice as lib's REST demo, for consistency.
    implementation("io.javalin:javalin:6.3.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")
    implementation("org.slf4j:slf4j-simple:2.0.13")

    // Postgres persistence
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Kafka client — the outbox relay publishes with this directly, no framework.
    implementation("org.apache.kafka:kafka-clients:3.8.0")

    testImplementation("org.testcontainers:junit-jupiter:1.20.3")
    testImplementation("org.testcontainers:postgresql:1.20.3")
    testImplementation("org.testcontainers:kafka:1.20.3")
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter("5.12.1")
        }
    }
}

application {
    mainClass = "org.pk.practices.supplychain.BookingServiceApp"
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(23)
    }
}
