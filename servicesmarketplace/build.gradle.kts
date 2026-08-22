/*
 * Local Services Marketplace — first real implementation slice.
 *
 * Companion code to
 * lib/src/main/java/org/pk/practices/design/servicesmarketplace/DESIGN.md.
 * Kept as its own module, same rationale as `cabreservation`/`supplychain`:
 * Postgres client jars shouldn't bleed into every other demo's classpath.
 *
 * Deliberately no Redis/H3 here, unlike `cabreservation` — see the phased
 * implementation plan's Context section for why (Pro service areas are
 * static, not a live-tracking problem; matching is a plain Postgres query).
 */

plugins {
    `java-library`
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // REST (Javalin + embedded Jetty) — same choice as cabreservation/supplychain, for consistency.
    implementation("io.javalin:javalin:6.3.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")
    implementation("org.slf4j:slf4j-simple:2.0.13")

    // Postgres persistence — source-of-truth state for everything in this module.
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")

    testImplementation("org.testcontainers:junit-jupiter:1.20.3")
    testImplementation("org.testcontainers:postgresql:1.20.3")
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter("5.12.1")
        }
    }
}

application {
    mainClass = "org.pk.practices.servicesmarketplace.ServicesMarketplaceApp"
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(23)
    }
}
