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
// `-PtargetService=ingest` for the OFF mirror ingest Job, or
// `-PtargetService=usda-ingest` for the USDA mirror ingest Job. All binaries
// share the same Gradle module / source set; only the entrypoint and image
// name differ.
val targetService: String = (project.findProperty("targetService") as String?) ?: "api"

val apiMainClass = "io.ktor.server.netty.EngineMain"
val ingestMainClass = "com.zenthek.ingest.IngestMainKt"
val usdaIngestMainClass = "com.zenthek.ingest.UsdaIngestMainKt"

val resolvedMainClass: String = when (targetService) {
    "ingest" -> ingestMainClass
    "usda-ingest" -> usdaIngestMainClass
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
            project.hasProperty("prod")                                   -> "gcr.io/fitzenio/fitzenia-api-prod"
            else                                                          -> "gcr.io/fitzenio-debug/fitzenia-api-dev"
        }
        tags = setOf("latest", System.getenv("TIMESTAMP") ?: System.currentTimeMillis().toString())
    }
    container {
        // Ingest Jobs have no listening port (Cloud Run Jobs run to completion);
        // we still declare 8080 so the API image keeps its current contract.
        val isIngest = targetService == "ingest" || targetService == "usda-ingest"
        ports = if (isIngest) emptyList() else listOf("8080")
        mainClass = resolvedMainClass
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

dependencies {
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.google.api.client)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
    implementation(libs.dotenv.kotlin)

    // Ktor Client for Http request
    implementation(libs.bundles.ktor.client)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlin.test.junit)
}
