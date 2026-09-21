import org.gradle.api.tasks.testing.logging.TestExceptionFormat

repositories {
    mavenCentral()
}

plugins {
    // Activate Kotlin support
    `kotlin-dsl`
}

val ktlint by configurations.creating

dependencies {
    // Use zip4j for robust handling of ZIP files
    implementation("net.lingala.zip4j:zip4j:2.6.3")

    // Use the Kotlin test library.
    testImplementation(kotlin("test"))

    // Use the Kotlin JUnit integration.
    testImplementation(kotlin("test-junit"))

    implementation("com.google.code.gson:gson:2.8.6")

    // Use ktlint for auto-formatting and linting
    ktlint("com.pinterest:ktlint:0.37.2")
}

tasks {
    test {
        testLogging.showExceptions = true
        testLogging.exceptionFormat = TestExceptionFormat.FULL
    }
}

// Ktlint tasks:
val ktlintOutputDir = "${project.layout.buildDirectory}/reports/ktlint/"
val ktlintInputFiles = project.fileTree(mapOf("dir" to "src", "include" to "**/*.kt"))

val ktlintCheck by tasks.creating(JavaExec::class) {
    inputs.files(ktlintInputFiles)
    outputs.dir(ktlintOutputDir)

    description = "Check Kotlin code style."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args = listOf("src/**/*.kt")
}

val ktlintFormat by tasks.creating(JavaExec::class) {
    inputs.files(ktlintInputFiles)
    outputs.dir(ktlintOutputDir)

    description = "Fix Kotlin code style deviations."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args = listOf("-F", "src/**/*.kt")
}
