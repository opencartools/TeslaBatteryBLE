// SPDX-License-Identifier: AGPL-3.0-only
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.wire)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

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
    // The records and history read Tesla's messages, which :protocol generates.
    api(project(":protocol"))
    // Tests use kotlin.test so they can move to commonTest (ADR-0007).
    testImplementation(kotlin("test"))
}

wire {
    // The app's own records (ADR-0008). They import Tesla's protos, which
    // :protocol vendors and generates; here they only resolve the imports.
    sourcePath {
        srcDir("src/main/proto-teslable")
    }
    protoPath {
        srcDir("../protocol/src/main/proto")
    }
    kotlin {
    }
}

// Wire-generated protos follow their own conventions. The task sources are
// narrowed by absolute path: the plugin matches string patterns against
// paths relative to each source root, where "build/generated" never
// appears, so a filter exclude cannot match it.
tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask>().configureEach {
    // Snapshot to a plain list: deriving from `source` lazily would recurse.
    val lintSources = source.files.filter { !it.invariantSeparatorsPath.contains("/build/generated/") }
    setSource(lintSources)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"), rootProject.file("config/detekt/portable.yml"))
    baseline = file("detekt-baseline.xml")
}

tasks.test {
    // ThinningReplayTest reads real logs from this directory and is skipped without it; as an input, setting or
    // changing it reruns the tests instead of reusing a result from without it.
    inputs.property("replayLogs", providers.environmentVariable("TESLABLE_REPLAY_LOGS").orElse(""))
}
