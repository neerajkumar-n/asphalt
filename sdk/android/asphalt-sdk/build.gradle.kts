plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    `maven-publish`
}

android {
    namespace = "io.asphalt.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24  // Android 7.0 - covers ~95% of active Android devices
        targetSdk = 34

        // Embed SDK version into BuildConfig for runtime reporting
        buildConfigField("String", "SDK_VERSION", "\"1.0.0\"")
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // Fused Location Provider
    implementation("com.google.android.gms:play-services-location:21.2.0")

    // WorkManager for reliable background uploads
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Room for offline event storage
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "io.asphalt"
            artifactId = "asphalt-sdk"
            version = "1.0.0"

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("Asphalt SDK")
                description.set("Android SDK for road anomaly detection using smartphone sensors.")
                url.set("https://github.com/asphalt-maps/asphalt")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
            }
        }
    }
}
