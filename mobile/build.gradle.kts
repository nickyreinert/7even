// Only kotlinMultiplatform is declared here, because :shared must configure
// without it. The Android/Compose/KSP plugins are declared in
// androidApp/build.gradle.kts instead (with their own version, from the same
// catalog) — that file is only ever evaluated when settings.gradle.kts
// includes :androidApp, which it does only when an Android SDK is present.
// Declaring them here too, even as `apply false`, makes Gradle resolve AGP's
// plugin descriptor for every invocation regardless of whether :androidApp is
// included, which reintroduces exactly the SDK-less-build breakage
// settings.gradle.kts's `hasAndroidSdk` gate exists to prevent.
plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    // Resolve all Kotlin plugin variants once at the root. Without this,
    // :shared can put the Kotlin plugin on the classpath before :androidApp
    // resolves kotlin-android, leaving Gradle unable to verify its version.
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.composeCompilerPlugin) apply false
    alias(libs.plugins.ksp) apply false
}
