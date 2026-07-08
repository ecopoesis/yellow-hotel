plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(testFixtures(project(":core")))
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// `./gradlew :app-desktop:run` should find "Pokemon Yellow.gbc" in the repo root
tasks.withType<JavaExec> {
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("harness") {
    mainClass.set("gbc.app.HarnessKt")
    classpath = sourceSets["main"].runtimeClasspath
}

compose.desktop {
    application {
        mainClass = "gbc.app.MainKt"
    }
}
