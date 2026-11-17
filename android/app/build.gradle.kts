import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The release key stays on the maintainer's PC: android/keystore.properties (not in git) names
// the keystore file and its passwords. Without it, release builds come out unsigned, which is
// what F-Droid wants anyway (it signs with its own key).
val signingFile = rootProject.file("keystore.properties")
val signing = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use(::load) }

android {
    namespace = "io.github.kindleshard.elite8active"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.kindleshard.elite8active"
        minSdk = 31
        targetSdk = 34
        versionCode = 14
        versionName = "0.14"
    }

    signingConfigs {
        if (signingFile.exists()) {
            create("release") {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // Drops unused code and icons: the full Material icon set alone is most of the APK otherwise.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    // Reproducible output: no build-time stamps or local paths in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        compose = true
    }

    // Two builds of the same app. "full" (GitHub releases) also changes modes by what you're
    // doing, through Google Play services. "fdroid" uses only free libraries, as F-Droid requires.
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
        }
        create("fdroid") {
            dimension = "distribution"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Activity recognition and geofences, for automatic sound modes (full build only).
    "fullImplementation"("com.google.android.gms:play-services-location:21.3.0")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is only a stub in local unit tests.
    testImplementation("org.json:json:20240303")
}
