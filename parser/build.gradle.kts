plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// This module is deliberately a plain Kotlin/JVM library. It must never gain an
// Android plugin or an Android dependency — that is what lets the whole parsing
// layer run under plain JUnit against the fixture corpus in milliseconds.
// See .md hard constraint #4.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    // Gradle's -D lands on the Gradle JVM, not the forked test JVM. Forward the
    // holdout opt-in explicitly so CorpusHoldoutTest can see it.
    systemProperty("mezgeb.holdout", providers.systemProperty("mezgeb.holdout").getOrElse("false"))
}
