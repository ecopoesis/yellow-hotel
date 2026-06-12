import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        extraWarnings.set(true)
    }
}

dependencies {
    implementation(libs.arrow.core)
    testFixturesImplementation(libs.arrow.core)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotest.assertions.arrow)
}

// Fast unit tests only; accuracy (test-ROM) and perf suites run via dedicated tasks.
tasks.test {
    useJUnitPlatform()
    systemProperty("kotest.tags", "!Accuracy & !Perf")
}

val accuracyTest by tasks.registering(Test::class) {
    description = "Runs hardware test-ROM accuracy suites (Blargg, Mooneye, acid2)"
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("kotest.tags", "Accuracy")
}

val perfTest by tasks.registering(Test::class) {
    description = "Runs headless real-time-multiple performance gate"
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("kotest.tags", "Perf")
}

kover {
    reports {
        verify {
            rule("core emulation must be fully covered") {
                bound {
                    minValue.set(100)
                    coverageUnits.set(CoverageUnit.LINE)
                    aggregationForGroup.set(AggregationType.COVERED_PERCENTAGE)
                }
            }
        }
    }
}
