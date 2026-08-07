/*
 * Cab Reservation Platform — first real implementation slice.
 *
 * Companion code to lib/src/main/java/org/pk/practices/design/cabreservation/DESIGN.md.
 * Kept as its own module (rather than inside `lib`), same rationale as
 * `supplychain`: Postgres/Redis client jars shouldn't bleed into every other
 * demo's classpath.
 */

plugins {
    `java-library`
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // REST (Javalin + embedded Jetty) — same choice as lib's REST demo and supplychain, for consistency.
    implementation("io.javalin:javalin:6.3.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")
    implementation("org.slf4j:slf4j-simple:2.0.13")

    // Postgres persistence — Trip/Payment/Payout/Invoice/Rating source-of-truth state.
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Geospatial index (DESIGN.md §4.2) — H3 for region/shard-key computation,
    // Redis GEOADD/GEOSEARCH for the actual proximity query.
    implementation("redis.clients:jedis:5.2.0")
    implementation("com.uber:h3:4.1.1")

    testImplementation("org.testcontainers:junit-jupiter:1.20.3")
    testImplementation("org.testcontainers:postgresql:1.20.3")
    testImplementation("org.testcontainers:testcontainers:1.20.3")
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter("5.12.1")
        }
    }
}

application {
    mainClass = "org.pk.practices.cabreservation.CabReservationApp"
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(23)
    }
}
