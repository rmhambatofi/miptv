plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
 id("org.jetbrains.kotlin.kapt")
 id("com.google.dagger.hilt.android")
}
android {
 namespace = "com.miptv"
 compileSdk = 35
 defaultConfig { applicationId = "com.miptv"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
 buildFeatures { compose = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
kapt { correctErrorTypes = true }
dependencies {
 implementation(platform("androidx.compose:compose-bom:2024.12.01"))
 implementation("androidx.activity:activity-compose:1.9.3")
 implementation("androidx.compose.foundation:foundation")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.tv:tv-material:1.0.0")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
 implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
 implementation("androidx.media3:media3-exoplayer:1.5.1")
 implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
 implementation("androidx.media3:media3-ui:1.5.1")
 implementation("com.google.dagger:hilt-android:2.52")
 kapt("com.google.dagger:hilt-compiler:2.52")
 implementation("androidx.room:room-runtime:2.6.1")
 implementation("com.squareup.retrofit2:retrofit:2.11.0")
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
 testImplementation("junit:junit:4.13.2")
}
