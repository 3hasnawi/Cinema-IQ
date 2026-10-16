import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// YouTube Data API key for trailer search. Read from local.properties (git-ignored) as youtube.api.key,
// or from the YOUTUBE_API_KEY environment variable. Empty means the app falls back to scraping search results.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val youtubeApiKey: String = (localProps.getProperty("youtube.api.key") ?: System.getenv("YOUTUBE_API_KEY") ?: "").trim()
// TMDB API key (themoviedb.org, v3) for sharp artwork and synopses. local.properties: tmdb.api.key, or env TMDB_API_KEY.
// Empty means the app keeps using the source's own artwork.
val tmdbApiKey: String = (localProps.getProperty("tmdb.api.key") ?: System.getenv("TMDB_API_KEY") ?: "").trim()

val omdbApiKey = (localProps.getProperty("omdb.api.key") ?: System.getenv("OMDB_API_KEY") ?: "").trim()

android {
    namespace = "com.cinemaplus.rebuilt"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.cinemaplus.rebuilt"
        minSdk = 24
        targetSdk = 36
        versionCode = 72
        versionName = "4.30"
        buildConfigField("String", "YOUTUBE_API_KEY", "\"$youtubeApiKey\"")
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        buildConfigField("String", "OMDB_API_KEY", "\"$omdbApiKey\"")
        testInstrumentationRunner = "com.cinemaplus.rebuilt.ui.PlayerSmokeInstrumentation"
    }
    buildTypes {
        release {
            // R8 shrinks and optimises the code and drops unused resources: a smaller APK and a faster start.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.3.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.tv:tv-material:1.0.1")
    implementation("androidx.tvprovider:tvprovider:1.0.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
