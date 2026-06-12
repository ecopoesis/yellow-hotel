plugins {
    // Auto-provisions the JDK 21 toolchain if not installed locally
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "yellow-hotel"

include(":core")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
