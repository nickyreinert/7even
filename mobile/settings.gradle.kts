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

// :androidApp is added once an Android SDK is available in the build environment.
// It is deliberately NOT included here so that `./gradlew build` stays green on a
// machine without the SDK — the shared engine is the part that must always build.
// include(":androidApp")
