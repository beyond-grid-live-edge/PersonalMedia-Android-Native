plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// STR-5B is explicitly opt-in. Never label the ordinary APK torrent-enabled.
val pmNativeTorrent = providers.gradleProperty("pmTorrent")
    .map { it.equals("true", ignoreCase = true) }.getOrElse(false)
val pmTorrentAbis = providers.gradleProperty("pmTorrentAbis").orNull
// M05b: pass the native dependency directory as a CMake argument. Gradle's
// already-running daemon may not forward newly set PowerShell environment vars.
val pmTorrentDepsRoot = providers.gradleProperty("pmTorrentDepsRoot").orNull

android {
    namespace = "com.personalmedia.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.personalmedia.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 200012
        versionName = "20.0.12-android-play-m05e"
        ndk {
            abiFilters += if (pmNativeTorrent && pmTorrentAbis == "arm64-v8a")
                listOf("arm64-v8a")
            else listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf("-DANDROID_STL=c++_static",
                    "-DPM_REQUIRE_LIBTORRENT=${if (pmNativeTorrent) "ON" else "OFF"}")
                if (pmNativeTorrent && !pmTorrentDepsRoot.isNullOrBlank()) {
                    arguments += "-DPM_TORRENT_DEPS_ROOT=${pmTorrentDepsRoot.replace('\\', '/')}"
                }
            }
        }
    }
    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    // Preserve the proven extracted-native-libraries runtime on tested phones.
    // All ELF LOAD segments must still be 16 KB compatible for Play distribution.
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("io.coil-kt:coil-compose:2.7.0")
    testImplementation("junit:junit:4.13.2")
}
