plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("com.google.devtools.ksp") }
android {
    namespace = "com.steptrack.offline"
    compileSdk = 34
    defaultConfig { applicationId = "com.steptrack.offline"; minSdk = 26; targetSdk = 34; versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1; versionName = "13" }
    signingConfigs {
        create("release") {
            storeFile = file("steptrack-release.jks")
            storePassword = System.getenv("KS_PASS") ?: "steptrack123"
            keyAlias = "steptrack"
            keyPassword = System.getenv("KS_PASS") ?: "steptrack123"
        }
    }
    buildTypes {
        release { signingConfig = signingConfigs.getByName("release"); isMinifyEnabled = false }
        debug { signingConfig = signingConfigs.getByName("release") }
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
