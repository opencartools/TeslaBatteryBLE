// SPDX-License-Identifier: AGPL-3.0-only
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.paparazzi)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

// Version identity comes from the nearest `v*` git tag, so tags are the only
// place a release version is written down:
//   v0.3.0-beta.3          -> versionName 0.3.0-beta.3
//   v0.3.0-beta.3 + 12 commits -> versionName 0.3.0-beta.3+12@6ac4527
// CI pull request builds (env PR_NUMBER, plus HEAD_SHA or GITHUB_SHA) name the PR
// and its head commit instead: 0.3.0-beta.3+pr85@6ac4527 (see ciVersionName).
// Everything after the "+" is semver build metadata: it never changes the
// release version, only says which build of it this is.
// versionCode is computed from the tag's semver: major * 1_000_000 +
// minor * 10_000 + patch * 100 + stage, where stage is the pre-release number
// (beta.3 -> 3, no number -> 0) and a stable release is 99, so it sorts above
// its own pre-releases. Use one pre-release series per patch version (do not
// mix alpha/beta/rc). That number is then multiplied by 1000 and the commits
// since the tag are added, so every build on main is an update over the one
// before it and still sorts below the next tagged release (a preview build is
// never a downgrade). Without git history (a tarball, a shallow clone with no
// tags) it falls back to 0.0.0-dev / 1.
val gitDescribe: String =
    try {
        providers
            .exec {
                commandLine("git", "describe", "--tags", "--match", "v[0-9]*")
                isIgnoreExitValue = true
            }.standardOutput.asText
            .get()
            .trim()
    } catch (_: Exception) {
        ""
    }
val tagVersion =
    Regex("""^v(\d+)[.](\d+)[.](\d+)(?:-([0-9A-Za-z.]+?))?(?:-(\d+)-g[0-9a-f]+)?$""")
        .matchEntire(gitDescribe)
val describedVersionName =
    if (tagVersion != null) {
        gitDescribe.removePrefix("v").replace(Regex("""-(\d+)-g([0-9a-f]+)$"""), "+\$1@\$2")
    } else {
        "0.0.0-dev"
    }

// Pull request number and head commit, set by CI. GITHUB_SHA on a pull_request
// event is a synthetic merge commit, so CI passes the real head as HEAD_SHA.
val ciPrNumber = providers.environmentVariable("PR_NUMBER").orNull.orEmpty()
val ciHeadSha =
    (providers.environmentVariable("HEAD_SHA").orNull ?: providers.environmentVariable("GITHUB_SHA").orNull).orEmpty()

fun ciVersionName(
    described: String,
    prNumber: String,
    headSha: String,
): String =
    if (prNumber.isBlank() || headSha.isBlank()) {
        described
    } else {
        "${described.substringBefore('+')}+pr$prNumber@${headSha.take(7)}"
    }

val gitVersionName = ciVersionName(describedVersionName, ciPrNumber, ciHeadSha)
val gitVersionCode: Int =
    tagVersion?.let { match ->
        val (majorText, minorText, patchText, preRelease, distanceText) = match.destructured
        val major = majorText.toInt()
        val minor = minorText.toInt()
        val patch = patchText.toInt()
        require(minor < 100 && patch < 100) { "minor and patch must stay below 100: $gitDescribe" }
        val stage =
            when {
                preRelease.isEmpty() -> 99
                else -> Regex("""\d+$""").find(preRelease)?.value?.toInt() ?: 0
            }
        require(preRelease.isEmpty() || stage < 99) { "pre-release number must stay below 99: $gitDescribe" }
        // Clamped so a long stretch without a tag never breaks a build; the CI
        // preview job refuses to publish once the real count reaches 1000.
        val distance = distanceText.ifEmpty { "0" }.toInt().coerceAtMost(999)
        val release = major.toLong() * 1_000_000 + minor * 10_000 + patch * 100 + stage
        val code = release * 1000 + distance
        // Int tops out at 2_147_483_647, i.e. about v2.14.x: past that, versionCode needs a new scheme.
        require(code <= Int.MAX_VALUE) { "versionCode overflows: $gitDescribe" }
        code.toInt()
    } ?: 1

// The owner-held signing identity, shared by local builds and CI (see
// keystore.properties, gitignored). Without it, debug builds fall back to the
// standard debug key so contributors can still build.
val keystoreProperties =
    Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

// CI debug builds (-PciDebugBuild=true, the Android CI `teslable-debug-apk`
// artifact) get their own application ID, launcher label and icon background
// (src/pr/res), so a PR build installs next to the release app instead of
// clashing with its signature.
// Preview, release and demo builds never set it and keep the real ID.
val ciDebugBuild = project.findProperty("ciDebugBuild") == "true"

// PR builds share one install slot (.pr) and are installed in any order, whichever PR's APK
// R picks next. Any increasing scheme (git distance, CI run number) makes an older run's APK
// a downgrade that Android refuses, so every PR build, CI or local, carries the same fixed
// code. Android accepts an update with an equal versionCode; the value sits above the codes
// PR builds had before this rule.
val prBuildVersionCode = 2_000_000_000

android {
    namespace = "com.dzid26.teslable"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dzid26.teslable"
        minSdk = 27
        targetSdk = 37
        versionCode = if (ciDebugBuild) prBuildVersionCode else gitVersionCode
        versionName = gitVersionName
        buildConfigField(
            "boolean",
            "DEMO_CAR",
            (project.findProperty("demoCar") as String?) ?: "false",
        )
        // Short tag for notifications of CI debug builds ("PR 332"); empty elsewhere.
        buildConfigField("String", "BUILD_LABEL", "\"\"")
        // android:label in the manifest; CI debug builds override it below.
        manifestPlaceholders["appLabel"] = "@string/app_name"
    }

    signingConfigs {
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    sourceSets {
        if (ciDebugBuild) getByName("debug").res.srcDir("src/pr/res")
        // The smoke build carries the simulated car from the debug source set,
        // so the emulator can drive the shrunk protocol and UI code end to end.
        maybeCreate("smoke").apply {
            // Kotlin sources: AGP built-in Kotlin compiles kotlin dirs, not extra java dirs.
            kotlin.srcDir("src/debug/java")
            manifest.srcFile("src/debug/AndroidManifest.xml")
        }
    }

    buildTypes {
        debug {
            // Every build shares the stable signing identity, so preview,
            // release and local builds update each other in place. PR builds
            // (-PciDebugBuild=true) keep the debug key instead: CI signs them with
            // the shared CI debug key, and a local PR build only updates a CI one
            // when ~/.android/debug.keystore holds that same key.
            if (!ciDebugBuild) signingConfigs.findByName("release")?.let { signingConfig = it }
            if (ciDebugBuild) {
                applicationIdSuffix = ".pr"
                manifestPlaceholders["appLabel"] = "TeslaBatteryBLE PR$ciPrNumber"
                buildConfigField("String", "BUILD_LABEL", "\"${if (ciPrNumber.isEmpty()) "PR build" else "PR $ciPrNumber"}\"")
            }
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // Only the rolling "Preview of main" build (-PpreviewDebuggable=true, set by CI)
            // is debuggable, so `adb run-as` can pull its logs. It keeps R8 and the release
            // key; tagged releases and the smoke build never set it.
            isDebuggable = project.findProperty("previewDebuggable") == "true"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // The release build (R8, resource shrinking, not debuggable) plus the
        // simulated car, for the emulator smoke test in CI:
        // `assembleSmoke -PdemoCar=true`. Its own application ID and the debug
        // key keep it apart from the real app; it is never published.
        create("smoke") {
            initWith(getByName("release"))
            applicationIdSuffix = ".smoke"
            manifestPlaceholders["appLabel"] = "TeslaBatteryBLE smoke"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    // The schema is exported so a future migration can be tested against it (ADR-0014).
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":protocol"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.vico.compose.m3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.leakcanary.android)
    testImplementation(libs.junit)
    testImplementation(libs.material.kolor)
    testImplementation(libs.paparazzi)
    testImplementation(libs.robolectric)
}

// Generated code (BuildConfig, etc.) follows its own conventions. The task
// sources are narrowed by absolute path: the plugin matches string patterns
// against paths relative to each source root, where "build/generated"
// never appears, so a filter exclude cannot match it.
tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask>().configureEach {
    // Snapshot to a plain list: deriving from `source` lazily would recurse.
    val lintSources = source.files.filter { !it.invariantSeparatorsPath.contains("/build/generated/") }
    setSource(lintSources)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
}
