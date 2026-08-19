plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompilerPlugin)
    alias(libs.plugins.ksp)
}

// The key the app uses to sign short-lived WebSocket handshake tokens for the
// ws-speedtest Worker (see src/index.js's isValidNativeAuth). Read from
// mobile/local.properties (gitignored, same file Android Studio writes
// sdk.dir into — Gradle does NOT load it as project properties on its own,
// unlike gradle.properties, so it's parsed explicitly here) or an env var, so
// the value never lands in source control. Computed at the top level,
// outside the android {} DSL, so it's a plain Kotlin val rather than
// something depending on which implicit receiver is in scope down in
// defaultConfig {}. An empty value still builds fine — NativeAuth.header()
// just returns null and the live test's streaming rounds fail closed with a
// clear error instead of silently connecting unauthenticated.
val localProperties = java.util.Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val nativeWsSecret: String = localProperties.getProperty("sevenNativeWsSecret")
    ?: System.getenv("SEVEN_NATIVE_WS_SECRET")
    ?: ""

android {
    namespace = "de.sevenapp.monitor.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.sevenapp.monitor"
        minSdk = 26 // Doze/background limits era; below this the model differs enough to not be worth supporting
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "SEVEN_WS_HMAC_SECRET", "\"$nativeWsSecret\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
}
