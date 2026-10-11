plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sethu.emergency"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sethu.emergency"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("org.codeshipping:llama-kotlin-android:0.1.0") {
        exclude(group = "androidx.core")
    }

    // Offline Vector Map Engine (Mapsforge - Phase 9B)
    implementation("org.mapsforge:mapsforge-map-android:0.24.1")
    implementation("org.mapsforge:mapsforge-themes:0.24.1")
    implementation("com.caverock:androidsvg:1.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}


