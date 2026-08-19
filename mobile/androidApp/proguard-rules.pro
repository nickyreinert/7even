# Room and Ktor rely on reflection over generated/serialized types.
-keep class de.sevenapp.monitor.** { *; }
-dontwarn org.slf4j.**
