import java.net.URI
import java.security.MessageDigest
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
    api(libs.arrow.core) // Option/Either appear in the core's public API
    testFixturesImplementation(libs.arrow.core)
    testFixturesImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotest.assertions.arrow)
    testImplementation(libs.kotlinx.serialization.json)
}

// SM83 SingleStepTests (per-instruction JSON cases incl. cycle-by-cycle bus activity).
// Too large to vendor (~31 MB compressed); fetched pinned + checksum-verified, Gradle-cached.
val sm83Commit = "f9c30210245dd691661db39f5ace022c465ecc2f"
val sm83Sha256 = "ac364307f2bc012034ae55b14d237f832cb3f3d9b14bb9c7c80086165d1afeaf"

val downloadSm83Tests by tasks.registering {
    description = "Downloads the SingleStepTests/sm83 JSON suite (pinned commit, SHA-256 verified)"
    group = "verification"
    val destProvider = layout.buildDirectory.dir("sm83-tests")
    outputs.dir(destProvider)
    outputs.upToDateWhen { destProvider.get().asFile.resolve(".complete-$sm83Commit").exists() }
    doLast {
        val dest = destProvider.get().asFile
        val marker = dest.resolve(".complete-$sm83Commit")
        if (marker.exists()) return@doLast
        dest.deleteRecursively()
        dest.mkdirs()
        val tarball = File.createTempFile("sm83", ".tar.gz")
        try {
            URI("https://github.com/SingleStepTests/sm83/archive/$sm83Commit.tar.gz")
                .toURL().openStream().use { input -> tarball.outputStream().use { out -> input.copyTo(out) } }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(tarball.readBytes())
                .joinToString("") { "%02x".format(it) }
            check(digest == sm83Sha256) {
                "SM83 suite checksum mismatch (expected $sm83Sha256, got $digest). " +
                    "GitHub may have recompressed the archive; re-verify content and update the pin."
            }
            val proc = ProcessBuilder(
                "tar", "-xzf", tarball.absolutePath, "-C", dest.absolutePath, "--strip-components=1",
            ).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            check(proc.waitFor() == 0) { "tar extraction failed: $out" }
            marker.createNewFile()
        } finally {
            tarball.delete()
        }
    }
}

// Fast unit tests only; accuracy (test-ROM) and perf suites run via dedicated tasks.
tasks.test {
    useJUnitPlatform()
    systemProperty("kotest.tags", "!Accuracy & !Perf")
}

val accuracyTest by tasks.registering(Test::class) {
    description = "Runs hardware test-ROM accuracy suites (Blargg, Mooneye, acid2, SM83 JSON)"
    group = "verification"
    dependsOn(downloadSm83Tests)
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("kotest.tags", "Accuracy")
    systemProperty("sm83.dir", layout.buildDirectory.dir("sm83-tests/v1").get().asFile.absolutePath)
    (findProperty("sm83.filter") as String?)?.let { systemProperty("sm83.filter", it) }
    systemProperty("testroms.dir", rootProject.layout.projectDirectory.dir("testroms").asFile.absolutePath)
    systemProperty("game.rom", rootProject.layout.projectDirectory.file("Pokemon Yellow.gbc").asFile.absolutePath)
}

val perfTest by tasks.registering(Test::class) {
    description = "Runs headless real-time-multiple performance gate"
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("kotest.tags", "Perf")
    systemProperty("game.rom", rootProject.layout.projectDirectory.file("Pokemon Yellow.gbc").asFile.absolutePath)
    systemProperty("perf.multiple", findProperty("perfMultiple") as String? ?: "4")
}

kover {
    currentProject {
        sources {
            // Test harness code, not emulation core
            excludedSourceSets.add("testFixtures")
        }
    }
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
