plugins {
    // API 36 (Google Play 2026) needs AGP 8.9.1+; 8.10 uses Gradle 8.11.1.
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
