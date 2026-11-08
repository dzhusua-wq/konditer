plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ua.konditer.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ua.konditer.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "2.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Постійний ключ підпису: нові версії встановлюються поверх старих без видалення каталогу
    signingConfigs {
        create("konditer") {
            storeFile = file("konditer.jks")
            storePassword = "konditer123"
            keyAlias = "konditer"
            keyPassword = "konditer123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("konditer")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("konditer")
        }
    }

    // Модель має лежати в APK нестиснутою
    androidResources {
        noCompress += "tflite"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val camerax = "1.4.1"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mediapipe:tasks-vision:0.10.20")

    testImplementation("junit:junit:4.13.2")
}
