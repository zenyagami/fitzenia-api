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

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

ktor {
    docker {
        jreVersion = JavaVersion.VERSION_21
        localImageName = "fitzenio-api"
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
        image = if (project.hasProperty("prod")) {
            "gcr.io/fitzenio/fitzenio-api-prod"
        } else {
            "gcr.io/fitzenio-debug/fitzenio-api-dev"
        }
        tags = setOf("latest", System.getenv("TIMESTAMP") ?: System.currentTimeMillis().toString())
    }
    container {
        ports = listOf("8080")
        mainClass = "io.ktor.server.netty.EngineMain"
        // Ensure resources are copied to the correct location in the container
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
