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

// The Android app is part of the project when an Android SDK is available.
include(":androidApp")
