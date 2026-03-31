plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.asphalt.demo"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.asphalt.demo"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            // 10.0.2.2 is the Android emulator's alias for the host machine's localhost.
            // For a real device on the same Wi-Fi as your dev machine, replace with your
            // machine's local IP (e.g. "http://192.168.1.42:8080/v1/ingest/batch").
            buildConfigField("String", "INGEST_URL", "\"http://10.0.2.2:8080/v1/ingest/batch\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Replace with your production backend URL before building a release APK.
            buildConfigField("String", "INGEST_URL", "\"https://your-backend.example.com/v1/ingest/batch\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        // BuildConfig is generated automatically for application modules but
        // declared explicitly here so INGEST_URL fields are clearly visible.
        buildConfig = true
    }
}

dependencies {
    // Asphalt SDK (local module reference; substitute with Maven coordinate in production)
    implementation(project(":asphalt-sdk"))

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
