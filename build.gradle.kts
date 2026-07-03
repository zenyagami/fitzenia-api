plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

group = "com.zenthek.fitzenio.rest"
version = "0.0.1"

kotlin {
    jvmToolchain(21)
}

// Build artifact selector. Default = the API server. Pass
// `-PtargetService=ingest` for the OFF mirror ingest Job,
// `-PtargetService=usda-ingest` for the USDA mirror ingest Job,
// `-PtargetService=coach` for the AI Coach Ktor service,
// `-PtargetService=coach-ingest` for the Coach KB ingest Cloud Run Job, or
// `-PtargetService=coach-retention` for the daily Coach retention-sweeper Job.
// All binaries share the same Gradle module / source set; only the entrypoint,
// config resource, and image name differ.
//
// The coach is a Ktor server (same EngineMain as the API) but boots a
// different module via `coach.conf` instead of `application.conf`. Locally,
// use the `runCoach` task; in the image, the config resource is selected via
// the `-Dconfig.resource=coach.conf` JVM flag set below.
val targetService: String = (project.findProperty("targetService") as String?) ?: "api"

val apiMainClass = "io.ktor.server.netty.EngineMain"
val ingestMainClass = "com.zenthek.ingest.IngestMainKt"
val usdaIngestMainClass = "com.zenthek.ingest.UsdaIngestMainKt"
val coachIngestMainClass = "com.zenthek.coach.ingest.CoachIngestMainKt"
val coachRetentionMainClass = "com.zenthek.coach.retention.CoachRetentionSweeperMainKt"
val coachRcSweeperMainClass = "com.zenthek.revenuecat.RevenueCatSweeperMainKt"

val resolvedMainClass: String = when (targetService) {
    "ingest" -> ingestMainClass
    "usda-ingest" -> usdaIngestMainClass
    "coach-ingest" -> coachIngestMainClass
    "coach-retention" -> coachRetentionMainClass
    "coach-rc-sweeper" -> coachRcSweeperMainClass
    // "coach" runs the same Ktor EngineMain as the API; coach.conf picks the module.
    else -> apiMainClass
}

application {
    mainClass = resolvedMainClass
}

ktor {
    docker {
        jreVersion = JavaVersion.VERSION_21
        localImageName = "fitzenia-api"
    }
}

// Configure Jib for Docker builds
configure<com.google.cloud.tools.jib.gradle.JibExtension> {
    from {
        image = "eclipse-temurin:21-jre"
        platforms {
            platform {
                architecture = "amd64"
                os = "linux"
            }
        }
    }
    to {
        image = when {
            targetService == "ingest" && project.hasProperty("prod")      -> "gcr.io/fitzenio/fitzenio-off-ingest"
            targetService == "ingest"                                     -> "gcr.io/fitzenio-debug/fitzenio-off-ingest-dev"
            targetService == "usda-ingest" && project.hasProperty("prod") -> "gcr.io/fitzenio/fitzenio-usda-ingest"
            targetService == "usda-ingest"                                -> "gcr.io/fitzenio-debug/fitzenio-usda-ingest-dev"
            targetService == "coach" && project.hasProperty("prod")             -> "gcr.io/fitzenio/fitzenia-coach-prod"
            targetService == "coach"                                          -> "gcr.io/fitzenio-debug/fitzenia-coach-dev"
            targetService == "coach-ingest" && project.hasProperty("prod")   -> "gcr.io/fitzenio/fitzenia-coach-ingest"
            targetService == "coach-ingest"                                   -> "gcr.io/fitzenio-debug/fitzenia-coach-ingest-dev"
            targetService == "coach-retention" && project.hasProperty("prod") -> "gcr.io/fitzenio/fitzenia-coach-retention"
            targetService == "coach-retention"                                -> "gcr.io/fitzenio-debug/fitzenia-coach-retention-dev"
            targetService == "coach-rc-sweeper" && project.hasProperty("prod") -> "gcr.io/fitzenio/fitzenia-coach-rc-sweeper"
            targetService == "coach-rc-sweeper"                               -> "gcr.io/fitzenio-debug/fitzenia-coach-rc-sweeper-dev"
            project.hasProperty("prod")                                   -> "gcr.io/fitzenio/fitzenia-api-prod"
            else                                                          -> "gcr.io/fitzenio-debug/fitzenia-api-dev"
        }
        tags = setOf("latest", System.getenv("TIMESTAMP") ?: System.currentTimeMillis().toString())
    }
    container {
        // Ingest Jobs have no listening port (Cloud Run Jobs run to completion);
        // we still declare 8080 so the API image keeps its current contract.
        val isIngest = targetService == "ingest" || targetService == "usda-ingest" ||
            targetService == "coach-ingest" || targetService == "coach-retention" ||
            targetService == "coach-rc-sweeper"
        ports = if (isIngest) emptyList() else listOf("8080")
        mainClass = resolvedMainClass
        // The coach image reuses EngineMain but must boot coach.conf, not the
        // default application.conf, so it loads the coach module.
        if (targetService == "coach") {
            jvmFlags = listOf("-Dconfig.resource=coach.conf")
        }
        workingDirectory = "/app"
    }
    extraDirectories {
        paths {
            path {
                setFrom(file("src/main/resources"))
                setInto("/app/resources")
            }
        }
    }
}

// Run the AI Coach Ktor service locally. Plain `./gradlew run` boots the API
// (application.mainClass is EngineMain + default application.conf); this task
// forces coach.conf so the coach module starts instead.
//   ./gradlew runCoach
tasks.register<JavaExec>("runCoach") {
    group = "application"
    description = "Run the AI Coach Ktor service locally (coach.conf)"
    mainClass.set("io.ktor.server.netty.EngineMain")
    classpath = sourceSets["main"].runtimeClasspath
    systemProperty("config.resource", "coach.conf")
}

dependencies {
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.cors)
    implementation(libs.google.api.client)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
    implementation(libs.dotenv.kotlin)
    implementation(libs.koog.agents)
    implementation(libs.koog.google)

    // Ktor Client for Http request
    implementation(libs.bundles.ktor.client)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlin.test.junit)
}
