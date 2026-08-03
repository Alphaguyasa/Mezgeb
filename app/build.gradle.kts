// Imported at the top rather than referenced as `java.util.Properties` inside
// `android { }`, where `java` resolves to Gradle's own extension instead of the package.
import java.util.Properties

plugins {
    // AGP 9 ships built-in Kotlin support and registers the `kotlin` extension
    // itself — applying org.jetbrains.kotlin.android here would collide with it.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    jacoco
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

    /**
     * Both languages ship in the base APK, never as downloadable splits.
     *
     * An App Bundle splits by language by default and Play delivers only the locale the
     * device is already set to. This app lets a shop pick its language in settings, so
     * an owner on an English phone choosing Amharic would be choosing resources that
     * were never installed. The usual remedy is Play Core's split installer, which
     * needs the network and the Play libraries — both ruled out by .md constraint
     * #1 — so the alternative is to not split at all.
     *
     * The cost is a few hundred kilobytes on an APK that is 3 MB. The bug it avoids is
     * invisible in every debug build and every sideloaded APK, because neither is a
     * bundle: it appears for the first time on a real Play install.
     */
    bundle {
        language {
            enableSplit = false
        }
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

    /**
     * Release signing, read from a gitignored `keystore.properties` beside this file.
     *
     * Absent that file the block does not register a signing config at all, and the
     * release build stays unsigned rather than falling back to the debug key. A debug
     * fallback is the friendlier default and the wrong one: it produces an APK that
     * installs, runs, and looks exactly like a release, signed with a key every Android
     * SDK on earth has the private half of.
     */
    val keystoreProperties: Properties? =
        rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
            Properties().apply { file.inputStream().use { stream -> load(stream) } }
        }

    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
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

    /**
     * Without this, coverage silently measures only the tests that do *not* use
     * Robolectric — 12 of this module's 42 test classes.
     *
     * Robolectric loads classes through its own instrumenting classloader, which
     * strips the debug information JaCoCo uses to map execution back to a source
     * line. JaCoCo's default is to discard those classes rather than guess, so 30
     * test classes' worth of DAO and lock coverage vanishes and the report looks
     * like a project with no tests instead of one with a measurement problem.
     */
    configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

/**
 * Coverage, reported rather than enforced.
 *
 * There is no minimum threshold and no build failure attached to this number. A
 * coverage gate is a rule about a proxy: it is satisfied by a test that executes a line
 * without asserting anything about it, and the cheapest way past a failing gate is
 * always the least useful test. The number is here to be read alongside the report,
 * which says *which* code is uncovered — that part is worth acting on.
 *
 * Generated and framework code is excluded, because counting Room's and Hilt's
 * generated implementations measures the code generators, not this project.
 */
jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.register<JacocoReport>("jacocoPlayDebugReport") {
    group = "verification"
    description = "Coverage for the play debug unit tests. Reported, not enforced."
    dependsOn("testPlayDebugUnitTest")

    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(false)
    }

    val generated = listOf(
        "**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*",
        // Room, Hilt and Dagger generated implementations.
        "**/*_Impl*.*", "**/*_Factory*.*", "**/*_MembersInjector*.*",
        "**/*_HiltModules*.*", "**/Hilt_*.*", "**/*_Hilt*.*",
        "**/hilt_aggregated_deps/**", "**/dagger/**",
        // Compose emits a singleton holder per file for lambdas, and previews are
        // not code anyone runs in production.
        "**/ComposableSingletons*.*", "**/*Preview*.*",
    )

    // AGP 9's built-in Kotlin support compiles to `intermediates/built_in_kotlinc`,
    // not the `tmp/kotlin-classes` path that every pre-AGP-9 example still shows. A
    // wrong path here does not fail — it produces a report of nothing, at 0%.
    classDirectories.setFrom(
        fileTree(
            layout.buildDirectory.dir(
                "intermediates/built_in_kotlinc/playDebug/compilePlayDebugKotlin/classes",
            ),
        ) {
            exclude(generated)
        },
    )
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(
        fileTree(layout.buildDirectory) { include("**/testPlayDebugUnitTest.exec") },
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

    // Background capture. WorkManager runs the direct flavor's inbox backfill;
    // DataStore holds the capture cursor, the import cursor and the health
    // timestamps. Neither library carries an HTTP client — WorkManager does declare
    // ACCESS_NETWORK_STATE for its network constraints, which the main manifest
    // removes explicitly. See .md constraint #1.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.datastore.preferences)

    // App lock. security-crypto keeps the PIN hash behind a Keystore-backed key, so
    // a stolen file cannot be brute-forced offline — which a four-digit PIN would
    // otherwise fall to in ten thousand guesses. Neither library touches the network.
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)

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
    testImplementation(libs.androidx.work.testing)
    // The span-selection interaction is worth a real UI test. Run under Robolectric
    // rather than on a device so it runs on every build — see RuleBuilderScreenTest.
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
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
