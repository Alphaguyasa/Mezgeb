plugins {
    // AGP 9 ships built-in Kotlin support and registers the `kotlin` extension
    // itself — applying org.jetbrains.kotlin.android here would collide with it.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "et.alpha.mezgeb"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "et.alpha.mezgeb"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        localeFilters += listOf("en", "am")
    }

    // Two distribution channels, one codebase. `play` is the Play-policy-compliant
    // build (notification capture only); `direct` is the sideloaded build that may
    // additionally read the SMS inbox. See 03-ARCHITECTURE.md section 1.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
        }
        create("direct") {
            dimension = "distribution"
            // Lets both flavors sit on one device during development.
            applicationIdSuffix = ".direct"
            versionNameSuffix = "-direct"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    // Schemas are committed to the repo so migrations can be diffed in review.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

/**
 * The bundled parser rules live in `:parser` and are contributed as a generated asset
 * rather than duplicated. `:parser`'s accuracy harness measures that exact file, so the
 * rules the app ships are provably the rules the accuracy figure was measured against.
 * Two committed copies would eventually disagree, and the disagreement would be silent.
 */
abstract class SyncParserRules : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val target = outputDir.get().asFile
        target.mkdirs()
        source.get().asFile.copyTo(target.resolve("parser_rules.json"), overwrite = true)
    }
}

val syncParserRules = tasks.register<SyncParserRules>("syncParserRules") {
    source.set(
        project(":parser").layout.projectDirectory.file("src/main/resources/parser_rules.json"),
    )
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            syncParserRules,
            SyncParserRules::outputDir,
        )
    }
}

/**
 * Robolectric normally downloads its `android-all` runtime itself, on the first test
 * that needs it, straight into `~/.m2`. That fetch happens inside the test JVM with no
 * progress output and no Gradle-level retry, and when it stalls the build simply hangs.
 *
 * Instead: let Gradle resolve the jar as a normal dependency, stage it, and run
 * Robolectric in offline mode pointed at that directory. Deterministic, visible in the
 * dependency graph, and works on a machine with a cold `~/.m2`.
 */
val robolectricRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val robolectricJarsDir: Provider<Directory> = layout.buildDirectory.dir("robolectric-runtime")

val stageRobolectricRuntime by tasks.registering(Copy::class) {
    from(robolectricRuntime)
    into(robolectricJarsDir)
}

tasks.withType<Test>().configureEach {
    dependsOn(stageRobolectricRuntime)
    systemProperty("robolectric.offline", "true")
    systemProperty(
        "robolectric.dependency.dir",
        robolectricJarsDir.get().asFile.absolutePath,
    )
}

dependencies {
    // The parsing engine. Pure JVM — see .md hard constraint #4.
    implementation(project(":parser"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.androidx.navigation.compose)

    // Persistence. Room is local-only by definition; no network stack.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    // Paging, for the ledger's transaction list (Session 5 consumes it).
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // DI. Hilt's processor runs through KSP; no network stack is pulled in.
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    // Room's in-memory database needs a Context, so DAO tests run under Robolectric
    // rather than on a device.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    robolectricRuntime(libs.robolectric.android.all)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
