pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "seven-monitor"

// :shared is the portable measurement engine — pure Kotlin, no platform imports.
// See mobile/README.md for why that boundary is enforced rather than merely intended.
include(":shared")

// :androidApp needs an Android SDK to configure at all (AGP resolves
// platform/build-tools during evaluation, before any task runs) — including
// it unconditionally would break `./gradlew build` on a machine without one,
// and the shared engine is the part that must always build there. So it's
// included only when an SDK is actually findable, the same way AGP itself
// would look for one: ANDROID_HOME/ANDROID_SDK_ROOT, or sdk.dir in
// local.properties (what Android Studio writes there for you).
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.isFile && it.readText().contains("sdk.dir") }

if (hasAndroidSdk) {
    include(":androidApp")
}
