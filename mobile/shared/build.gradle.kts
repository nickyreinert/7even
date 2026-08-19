plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    // JVM is the only target wired up today. It is what lets this module be
    // compiled and unit-tested on a machine with no Android SDK and no Xcode,
    // which is the whole point: the measurement engine is verified on its own,
    // away from either platform.
    //
    // Adding the real targets later is additive, not a rewrite:
    //   androidTarget()
    //   iosArm64(); iosSimulatorArm64()
    // commonMain already compiles against no platform API, so those targets
    // inherit the engine for free.
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            // api, not implementation: TimeZone appears in the public
            // signatures of ReportSchedule and MonitorCoordinator, so
            // consumers need it on their compile classpath.
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The KMP payoff depends entirely on commonMain staying free of platform
// imports, and that is the kind of rule that quietly rots under deadline
// pressure. Enforce it in the build instead of trusting code review.
val checkNoPlatformImports by tasks.registering {
    group = "verification"
    description = "Fails if commonMain imports an Android, Java or Apple API."

    val commonMainDir = layout.projectDirectory.dir("src/commonMain")
    inputs.dir(commonMainDir)
    // No meaningful file output; this task is a gate, so give it a stamp file
    // rather than letting Gradle treat it as perpetually out of date.
    val stamp = layout.buildDirectory.file("checkNoPlatformImports.stamp")
    outputs.file(stamp)

    doLast {
        val banned = Regex("""^\s*import\s+(android\.|androidx\.|java\.|javax\.|platform\.|kotlinx\.cinterop)""")
        val offenders = mutableListOf<String>()

        commonMainDir.asFile.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { idx, line ->
                    if (banned.containsMatchIn(line)) {
                        offenders += "${file.relativeTo(commonMainDir.asFile)}:${idx + 1}: ${line.trim()}"
                    }
                }
            }

        if (offenders.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("commonMain must not depend on a platform — found ${offenders.size} violation(s):")
                    offenders.forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("Move the platform-specific part behind an interface in commonMain and")
                    appendLine("implement it in the platform source set (or via expect/actual).")
                }
            )
        }
        stamp.get().asFile.writeText("ok\n")
    }
}

tasks.named("check") { dependsOn(checkNoPlatformImports) }
